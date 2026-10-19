package com.plox.llmtranslate.domain.runtime

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.plox.llmtranslate.domain.ModelRetentionProvider
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.util.WavReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм STT на sherpa-onnx: загрузка модели и распознавание WAV.
 *
 * Тип модели определяется по файлам каталога ([detectModel], ревью #83 —
 * жёсткий детект по каноническим sherpa-суффиксам, не по подстрокам):
 * - каноническая тройка `*.encoder.onnx`/`*.decoder.onnx`/`*.joiner.onnx`
 *   → transducer;
 * - одиночный `model*.onnx` (NeMo CTC / piper-подобная раскладка), если
 *   канонической тройки нет → nemo_ctc — проверяется ДО whisper-ветки:
 *   имена вроде `model_decoder.onnx` содержат подстроку «decoder», но
 *   канонический суффикс `.decoder.onnx` у них отсутствует;
 * - только каноническая пара `*.encoder.onnx` + `*.decoder.onnx` → whisper;
 * - любое иное сочетание — [RuntimeError.LoadFailed] с перечислением
 *   найденных ONNX-файлов вместо угадывания типа.
 *
 * Для whisper-моделей язык распознавания задаётся в [transcribe]; при его смене
 * распознаватель пересоздаётся.
 *
 * Модели хранятся LRU-кэшем пучков на общем каркасе [EngineCacheScaffold]
 * (issue #37, по образцу #36): при настройке keep движок удерживает
 * загруженные STT-модели и повторный выбор модели не выполняет загрузку
 * заново (быстрый путь каркаса [EngineCacheScaffold.reuseCachedUnlocked]);
 * при unload — держит только текущую модель.
 *
 * Поверх каркаса остаётся только sherpa-специфика: определение типа модели
 * по файлам ([detectModel]), построение [OfflineRecognizer] ([buildRecognizer])
 * и пересоздание распознавателя при смене языка whisper — пучок держит
 * [ModelBundle.recognizer] в var (каркас пучков не инспектирует).
 * Освобождение нативного распознавателя — [ModelBundle.release]
 * (гарантия повторного release() при сбое пересоздания языка — в [transcribe]).
 */
@Singleton
class SherpaSttEngine @Inject constructor(
    private val retentionProvider: ModelRetentionProvider
) : SttEngine {

    companion object {
        const val SAMPLE_RATE = 16000

        /**
         * Причина InferenceFailed при пустом результате распознавания
         * (issue #69: общая константа — смоук-тест сверяется с ней через
         * [com.plox.llmtranslate.domain.model.userMessage], а не с литералом).
         */
        const val EMPTY_TRANSCRIPTION_REASON = "Распознавание не выполнено"

        private const val TYPE_TRANSDUCER = "transducer"
        private const val TYPE_NEMO_CTC = "nemo_ctc"
        private const val TYPE_WHISPER = "whisper"

        /**
         * Явное число потоков инференса sherpa (ревью #83): без numThreads в
         * [OfflineModelConfig] sherpa считает дефолтным 1 — инференс шёл в
         * четверть ресурсов движка; значение согласовано с
         * Piper-рантаймом (см. PiperTtsRuntime.NUM_THREADS).
         */
        private const val STT_NUM_THREADS = 2

        /**
         * Канонические суффиксы sherpa-файлов (ревью #83): transducer/whisper
         * распознаются только по окончаниям имён (без учёта регистра).
         */
        private const val SUFFIX_ENCODER = ".encoder.onnx"
        private const val SUFFIX_DECODER = ".decoder.onnx"
        private const val SUFFIX_JOINER = ".joiner.onnx"
    }

    /**
     * Пучок sherpa-модели: нативный распознаватель и метаданные одной модели.
     * [recognizer] и [whisperLanguage] — var: для whisper распознаватель
     * пересоздаётся при смене языка распознавания.
     */
    private class ModelBundle(
        val info: LoadedModelInfo,
        val type: String,
        val modelMeta: ModelInfo,
        var recognizer: OfflineRecognizer,
        var whisperLanguage: String?
    ) {
        /** Освободить нативный распознаватель пучка (ошибки — только в лог). */
        fun release() {
            try {
                recognizer.release()
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при разгрузке sherpa")
            }
        }
    }

    /**
     * Каркас LRU-кэша пучков: распознавание, загрузка и разгрузка
     * сериализуются внутренним мьютексом каркаса, поэтому release() ждёт
     * окончания активного decode (use-after-free исключён). Распознавание
     * может идти десятки секунд — для onStop-разгрузки это приемлемо,
     * release() вызывается вне главного потока.
     */
    private val engineCache = EngineCacheScaffold<ModelBundle>(
        capacity = EngineCacheScaffold.DEFAULT_MODEL_CACHE_CAPACITY,
        retentionProvider = retentionProvider,
        logLabel = "sherpa",
        releaseBundle = ModelBundle::release,
        infoOf = { it.info },
    )

    /** Состояние загруженных моделей; пишется только под мьютексом [engineCache]. */
    override val loadedModels: StateFlow<List<LoadedModelInfo>> = engineCache.loadedModels

    /**
     * Загрузить sherpa-модель, поместив пучок в LRU-кэш. При настройке keep
     * пучки других моделей сохраняются (сверх ёмкости вытесняются наименее
     * давно используемые), при unload — движок держит ТОЛЬКО текущую модель:
     * чужие пучки выгружаются и перед загрузкой, и при попадании в кэш
     * (прежнее поведение).
     * @return null при успехе или [RuntimeError] при ошибке.
     */
    override suspend fun ensureLoaded(model: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = installedModelPath(model)
                ?: return@withContext RuntimeError.ModelNotReady

            engineCache.sessionMutex.withLock {
                // Быстрый путь под мьютексом: пучок уже в кэше — делать
                // активным без пересоздания распознавателя (повторный
                // выбор модели, issue #37). Политика применяется и здесь:
                // в unload-режиме движок обязан держать только текущую модель.
                if (engineCache.reuseCachedUnlocked(model.id)) {
                    return@withContext null
                }

                // Политика выгрузки читается на каждую загрузку — смена
                // настройки применяется сразу.
                engineCache.applyRetentionUnlocked()

                guardModelLoad(
                    label = "sherpa",
                    engineTag = "sherpa",
                    path = path,
                    // Частично созданных сессий у sherpa нет: распознаватель
                    // строится одним вызовом и при сбое не создаётся вовсе.
                    closePartial = {}
                ) {
                    // Ревью #83: при неоднозначной раскладке — явная ошибка с
                    // перечислением фактических файлов каталога, не угадывание.
                    val meta = when (val detection = detectModel(File(path))) {
                        is SttLayoutDetection.Layout -> detection.info
                        is SttLayoutDetection.Unknown -> {
                            val filesDescription =
                                (if (detection.missingTokens) "нет tokens.txt; " else "") +
                                    "ONNX-файлы: " +
                                    detection.onnxFiles.joinToString(", ").ifEmpty { "нет" }
                            Timber.e(
                                "Не удалось определить тип sherpa-модели: $path — $filesDescription"
                            )
                            return@withContext RuntimeError.LoadFailed(
                                "sherpa",
                                "Не удалось определить тип модели по файлам: $filesDescription"
                            )
                        }
                    }
                    val whisperLanguage = if (meta.type == TYPE_WHISPER) "auto" else null
                    // Граница отмены перед долгим нативным построением
                    // распознавателя (секунды-десятки секунд, не прерываемо).
                    // Пучок ещё не в кэше — при отмене/ошибке чужие пучки целы.
                    currentCoroutineContext().ensureActive()
                    val recognizer = buildRecognizer(meta, whisperLanguage)
                    engineCache.putBundleUnlocked(
                        model.id,
                        ModelBundle(
                            // Ревью #83: языки модели публикуются в карточке
                            // «Загружено в память» (у STT-пучка они были пусты).
                            info = LoadedModelInfo(
                                modelId = model.id,
                                displayName = model.displayName,
                                category = ModelCategory.STT,
                                ramRequiredMb = model.ramRequiredMb,
                                languages = model.languages
                            ),
                            type = meta.type,
                            modelMeta = meta,
                            recognizer = recognizer,
                            whisperLanguage = whisperLanguage
                        )
                    )
                    engineCache.publishActiveUnlocked(model.id)
                    Timber.d("Sherpa-модель загружена (${meta.type}): $path")
                    null
                }
            }
        }

    /**
     * Распознать речь в WAV-файле по активному пучку кэша.
     * @param language подсказка языка (ru/en/ne); для whisper переключает распознавание,
     *   null (AUTO) — возвращает мультиязычный режим "auto".
     * @return текст или [RuntimeError].
     */
    override suspend fun transcribe(wavPath: String, language: String?): Result<String> =
        withContext(Dispatchers.IO) {
            engineCache.sessionMutex.withLock {
                val bundle = engineCache.activeBundle()
                    ?: return@withContext Result.failure(RuntimeError.ModelNotSelected)

                // Для whisper при смене языка пересоздаём распознаватель.
                // AUTO (language == null) — эффективный "auto": без этого после
                // распознавания с явной подсказкой мультиязычный whisper
                // оставался бы зафиксирован на прошлом языке (ревью).
                val effectiveLanguage = language ?: "auto"
                if (bundle.type == TYPE_WHISPER && effectiveLanguage != bundle.whisperLanguage) {
                    val oldRecognizer = bundle.recognizer
                    // Ревью: локальный флаг — повторный release() в catch только
                    // если первый не выполнился (double-release нативного
                    // распознавателя sherpa иначе возможен при сбое buildRecognizer).
                    var released = false
                    try {
                        // Обязательно освобождаем старый распознаватель — иначе
                        // утечка сотен МБ нативной памяти при каждой смене языка.
                        oldRecognizer.release()
                        released = true
                        bundle.recognizer = buildRecognizer(bundle.modelMeta, effectiveLanguage)
                        bundle.whisperLanguage = effectiveLanguage
                        Timber.d("Whisper переключён на язык: $effectiveLanguage")
                    } catch (e: Exception) {
                        // Старый распознаватель освобождён (или освобождение
                        // бросило), новый не создан — пучок неконсистентен:
                        // снимаем его с кэша и с активного id (пучки других
                        // моделей не затрагиваются).
                        Timber.e(e, "Не удалось пересоздать whisper для языка $effectiveLanguage")
                        // Если исключение бросил сам recognizer.release(), старый
                        // нативный распознаватель мог пережить снятие пучка —
                        // повторная попытка release() только по неосвобождённой
                        // ссылке (успешно освобождённый повторно не освобождаем,
                        // неудача — утечка логируется, ревью).
                        if (!released) {
                            try {
                                oldRecognizer.release()
                            } catch (e2: Exception) {
                                Timber.e(
                                    e2,
                                    "Не удалось освободить recognizer пучка ${bundle.info.modelId} — " +
                                        "нативная память может утечь"
                                )
                            }
                        }
                        val failedId = engineCache.activeId
                        if (failedId != null) {
                            // Снятие пучка с кэша БЕЗ releaseBundle: распознаватель
                            // уже освобождён (или попытка бросила) выше —
                            // повторный release был бы double-release.
                            engineCache.cache.remove(failedId)
                            engineCache.activeId = null
                        }
                        engineCache.publishLoadedUnlocked()
                        return@withContext Result.failure(
                            RuntimeError.LoadFailed(
                                "sherpa",
                                "Не удалось переключить язык: ${e.message ?: "Неизвестная ошибка"}"
                            )
                        )
                    }
                }

                val rec = bundle.recognizer

                val file = File(wavPath)
                if (!file.exists()) {
                    return@withContext Result.failure(
                        RuntimeError.InferenceFailed("sherpa", "Файл не найден: $wavPath")
                    )
                }

                try {
                    val samples = WavReader.readPcmAsFloat(file)
                    if (samples.isEmpty()) {
                        return@withContext Result.failure(
                            RuntimeError.InferenceFailed("sherpa", "Пустой аудиофайл")
                        )
                    }

                    // Граница отмены между чтением WAV и нативным decode —
                    // сам decode (десятки секунд) не прерываем, отмена срабатывает
                    // на фазах вокруг него.
                    currentCoroutineContext().ensureActive()
                    val stream = rec.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        rec.decode(stream)
                        // Граница отмены после decode: отменённая операция
                        // не отдаёт результат, stream освобождается в finally.
                        currentCoroutineContext().ensureActive()
                        val text = rec.getResult(stream).text.trim()
                        if (text.isEmpty()) {
                            Result.failure(
                                RuntimeError.InferenceFailed(
                                    "sherpa", EMPTY_TRANSCRIPTION_REASON
                                )
                            )
                        } else {
                            Result.success(text)
                        }
                    } finally {
                        stream.release()
                    }
                } catch (e: CancellationException) {
                    // Отмена распознавания кнопкой «Отмена» — не ошибка:
                    // всплываем выше, чтобы UI возвращался в idle без ошибки.
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка распознавания sherpa")
                    Result.failure(
                        RuntimeError.InferenceFailed("sherpa", e.message ?: "Неизвестная ошибка")
                    )
                }
            }
        }

    /**
     * Разгрузить ВСЕ удерживаемые движком модели. Ждёт окончания активного
     * распознавания (мьютекс [engineCache]).
     */
    override suspend fun release() = engineCache.release()

    /**
     * Извлечь и освободить пучок модели [modelId] (удаление модели, issue #40).
     * Ждёт окончания активного распознавания под мьютексом [engineCache]; если
     * пучок был активным — сбрасывается активный id и публикуется новый
     * список [loadedModels]. Отсутствующий в кэше id — no-op.
     */
    override suspend fun releaseModel(modelId: String) = engineCache.releaseModel(modelId)

    /**
     * Метаданные модели (тип и пути к файлам).
     */
    internal data class ModelInfo(
        val type: String,
        val tokensPath: String,
        val encoderPath: String?,
        val decoderPath: String?,
        val joinerPath: String?,
        val nemoModelPath: String?
    )

    /**
     * Результат распознавания раскладки STT-каталога (ревью #83): вместо
     * nullable-результата — явный провал со списком найденных файлов, чтобы
     * [RuntimeError.LoadFailed] в UI перечислял фактическое содержимое
     * каталога, а не только факт неоднозначности.
     */
    internal sealed interface SttLayoutDetection {
        /** Раскладка распознана: метаданные готовы к построению распознавателя. */
        data class Layout(val info: ModelInfo) : SttLayoutDetection

        /**
         * Раскладка не распознана (набор файлов не подпадает ни под одно
         * правило): [onnxFiles] — имена ONNX-файлов каталога,
         * [missingTokens] — отсутствует обязательный `tokens.txt`.
         */
        data class Unknown(
            val onnxFiles: List<String>,
            val missingTokens: Boolean
        ) : SttLayoutDetection
    }

    /**
     * Определить тип модели по файлам каталога (полные правила — в KDoc класса).
     *
     * Ревью #83 — жёсткий детект по каноническим окончаниям имён вместо
     * произвольных подстрок: прежняя эвристика `contains("encoder"/"decoder"/
     * "joiner")` ошибочно относила к whisper пакеты NeMo CTC
     * (`model_decoder.onnx` содержит «decoder») и transducer-наборы с
     * `joint.onnx`. Теперь:
     * - transducer — только полная каноническая тройка `.encoder.onnx`/
     *   `.decoder.onnx`/`.joiner.onnx` (endsWith, без учёта регистра);
     * - одиночный NeMo/piper-подобный `model*.onnx` (по образцу
     *   [PiperTtsRuntime.discoverLayout]) — приоритет перед whisper-веткой,
     *   канонические sherpa-суффиксы при этом исключены;
     * - whisper — только каноническая пара `.encoder.onnx` + `.decoder.onnx`;
     * - иное — [SttLayoutDetection.Unknown] с перечислением файлов.
     *
     * Состав файлов пучков каталога зафиксирован манифестом
     * (models/manifest.json); для импортируемых вручную наборов с
     * нестандартными именами вместо угадывания возвращается явная ошибка.
     */
    internal fun detectModel(dir: File): SttLayoutDetection {
        val files = dir.listFiles()?.toList() ?: emptyList()
        val onnxFiles = files.filter { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
        val onnxNames = onnxFiles.map { it.name }
        val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" }
            ?: return SttLayoutDetection.Unknown(onnxNames, missingTokens = true)

        fun bySuffix(suffix: String): File? =
            onnxFiles.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }

        val encoder = bySuffix(SUFFIX_ENCODER)
        val decoder = bySuffix(SUFFIX_DECODER)
        val joiner = bySuffix(SUFFIX_JOINER)

        fun canonical(name: String): Boolean =
            name.endsWith(SUFFIX_ENCODER, ignoreCase = true) ||
                name.endsWith(SUFFIX_DECODER, ignoreCase = true) ||
                name.endsWith(SUFFIX_JOINER, ignoreCase = true)

        // 1) Трансдьюсер — только полная каноническая тройка sherpa.
        if (encoder != null && decoder != null && joiner != null) {
            return SttLayoutDetection.Layout(
                ModelInfo(
                    type = TYPE_TRANSDUCER,
                    tokensPath = tokens.absolutePath,
                    encoderPath = encoder.absolutePath,
                    decoderPath = decoder.absolutePath,
                    joinerPath = joiner.absolutePath,
                    nemoModelPath = null
                )
            )
        }

        // 2) NeMo CTC / piper-подобный одиночный пучок model*.onnx — ДО
        //    whisper-ветки (ревью #83): подстроки «encoder»/«decoder» в таких
        //    именах каноническим суффиксом не являются.
        val nemo = onnxFiles.firstOrNull {
            it.name.startsWith("model", ignoreCase = true) && !canonical(it.name)
        }
        if (nemo != null) {
            return SttLayoutDetection.Layout(
                ModelInfo(
                    type = TYPE_NEMO_CTC,
                    tokensPath = tokens.absolutePath,
                    encoderPath = null,
                    decoderPath = null,
                    joinerPath = null,
                    nemoModelPath = nemo.absolutePath
                )
            )
        }

        // 3) Whisper — только каноническая пара (joiner-файл при наличии уже
        //    попал бы в ветку 1 или в nemo/Unknown).
        if (encoder != null && decoder != null) {
            return SttLayoutDetection.Layout(
                ModelInfo(
                    type = TYPE_WHISPER,
                    tokensPath = tokens.absolutePath,
                    encoderPath = encoder.absolutePath,
                    decoderPath = decoder.absolutePath,
                    joinerPath = null,
                    nemoModelPath = null
                )
            )
        }

        return SttLayoutDetection.Unknown(onnxNames, missingTokens = false)
    }

    /**
     * Собрать OfflineRecognizer по метаданным модели.
     * [STT_NUM_THREADS] задаётся явно во всех раскладках (ревью #83):
     * без него sherpa использует 1 поток инференса.
     */
    private fun buildRecognizer(info: ModelInfo, language: String?): OfflineRecognizer {
        val modelConfig = when (info.type) {
            TYPE_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = info.encoderPath!!,
                    decoder = info.decoderPath!!,
                    joiner = info.joinerPath!!
                ),
                tokens = info.tokensPath,
                numThreads = STT_NUM_THREADS
            )
            TYPE_WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = info.encoderPath!!,
                    decoder = info.decoderPath!!,
                    language = language ?: "auto",
                    task = "transcribe"
                ),
                tokens = info.tokensPath,
                numThreads = STT_NUM_THREADS
            )
            else -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = info.nemoModelPath!!),
                tokens = info.tokensPath,
                numThreads = STT_NUM_THREADS
            )
        }
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))
    }
}
