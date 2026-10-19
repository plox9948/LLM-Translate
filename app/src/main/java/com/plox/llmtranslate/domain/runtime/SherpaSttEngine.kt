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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм STT на sherpa-onnx: загрузка модели и распознавание WAV.
 *
 * Тип модели определяется по файлам каталога:
 * - есть "joiner" → transducer (encoder/decoder/joiner);
 * - есть encoder и decoder, но нет joiner → whisper;
 * - один файл вида model*.onnx → nemo_ctc.
 *
 * Для whisper-моделей язык распознавания задаётся в [transcribe]; при его смене
 * распознаватель пересоздаётся.
 *
 * Модели хранятся LRU-кэшем пучков (issue #37, по образцу #36): при настройке
 * keep движок удерживает загруженные STT-модели и повторный выбор модели
 * не выполняет загрузку заново (быстрый путь [ensureLoaded]); при unload —
 * держит только текущую модель.
 *
 * TODO(ревью): движок дублирует логику [EngineCacheScaffold]
 * (mutex + [EngineModelCache] + активный id + publish/eviction/retention).
 * Перевод на каркас отложен намеренно (объём велик для текущего цикла):
 * расхождения — каркас не покрывает пересоздание whisper-recognizer при
 * смене языка (var-поля пучка вместо immutable), освобождение пучка здесь
 * делегировано [ModelBundle.release] (шерпа-специфика), а у каркаса —
 * коллбэку [releaseBundle]; быстрая ветка unload здесь повторяет
 * [reuseCachedUnlocked] байт-в-байт. При переносе сохранить гарантию
 * повторного release() в ветке смены языка (ревью).
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
     * Защита нативного recognizer: распознавание, загрузка и разгрузка
     * сериализуются, поэтому release() ждёт окончания активного decode
     * (use-after-free исключён). Распознавание может идти десятки секунд —
     * для onStop-разгрузки это приемлемо, release() вызывается вне главного потока.
     * Вся работа с [cache] и [activeId] — только под этим мьютексом.
     */
    private val recognizerMutex = Mutex()

    /** LRU-кэш загруженных пучков моделей (issue #37). */
    private val cache = EngineModelCache<ModelBundle>(EngineCacheScaffold.DEFAULT_MODEL_CACHE_CAPACITY)

    /**
     * Id пучка, на котором работает [transcribe]; пишется и читается только
     * под [recognizerMutex].
     */
    private var activeId: String? = null

    private val _loadedModels = MutableStateFlow<List<LoadedModelInfo>>(emptyList())

    /** Состояние загруженных моделей; пишется только под [recognizerMutex]. */
    override val loadedModels: StateFlow<List<LoadedModelInfo>> = _loadedModels

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
            val path = model.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            recognizerMutex.withLock {
                val cached = cache.get(model.id)
                if (cached != null) {
                    // Быстрый путь под мьютексом: пучок уже в кэше — делать
                    // активным без пересоздания распознавателя (повторный
                    // выбор модели, issue #37). Политика применяется и здесь:
                    // в unload-режиме движок обязан держать только текущую модель.
                    if (!retentionProvider.keepModelsOnDirectionChange()) {
                        unloadOtherBundlesUnlocked(model.id)
                    }
                    activeId = model.id
                    publishLoadedUnlocked()
                    return@withContext null
                }

                // Политика выгрузки читается на каждую загрузку — смена
                // настройки применяется сразу.
                if (!retentionProvider.keepModelsOnDirectionChange()) {
                    releaseUnlocked()
                }

                try {
                    val meta = detectModel(File(path))
                    if (meta == null) {
                        Timber.e("Не удалось определить тип sherpa-модели: $path")
                        return@withContext RuntimeError.LoadFailed(
                            "sherpa", "Не удалось определить тип модели по файлам"
                        )
                    }
                    val whisperLanguage = if (meta.type == TYPE_WHISPER) "auto" else null
                    // Граница отмены перед долгим нативным построением
                    // распознавателя (секунды-десятки секунд, не прерываемо).
                    // Пучок ещё не в кэше — при отмене/ошибке чужие пучки целы.
                    currentCoroutineContext().ensureActive()
                    val recognizer = buildRecognizer(meta, whisperLanguage)
                    cache.put(
                        model.id,
                        ModelBundle(
                            info = LoadedModelInfo(
                                modelId = model.id,
                                displayName = model.displayName,
                                category = ModelCategory.STT,
                                ramRequiredMb = model.ramRequiredMb
                            ),
                            type = meta.type,
                            modelMeta = meta,
                            recognizer = recognizer,
                            whisperLanguage = whisperLanguage
                        )
                    )
                    activeId = model.id
                    cache.evictOverCapacity().forEach { (evictedId, bundle) ->
                        Timber.d("Sherpa: вытеснена из кэша модель $evictedId")
                        bundle.release()
                    }
                    publishLoadedUnlocked()
                    Timber.d("Sherpa-модель загружена (${meta.type}): $path")
                    null
                } catch (e: CancellationException) {
                    // Отмена загрузки кнопкой «Отмена» — не ошибка: пучок этого
                    // пути в кэш не положен, пучки других моделей остаются.
                    Timber.d("Загрузка sherpa-модели отменена: $path")
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось загрузить sherpa-модель $path")
                    RuntimeError.LoadFailed("sherpa", e.message ?: "Неизвестная ошибка")
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
            recognizerMutex.withLock {
                val bundle = activeId?.let { cache.get(it) }
                    ?: return@withContext Result.failure(RuntimeError.ModelNotSelected)

                // Для whisper при смене языка пересоздаём распознаватель.
                // AUTO (language == null) — эффективный "auto": без этого после
                // распознавания с явной подсказкой мультиязычный whisper
                // оставался бы зафиксирован на прошлом языке (ревью).
                val effectiveLanguage = language ?: "auto"
                if (bundle.type == TYPE_WHISPER && effectiveLanguage != bundle.whisperLanguage) {
                    try {
                        // Обязательно освобождаем старый распознаватель — иначе
                        // утечка сотен МБ нативной памяти при каждой смене языка.
                        bundle.recognizer.release()
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
                        // повторная попытка release() на удержанной ссылке
                        // (повторный destroy у sherpa безвреден; неудача —
                        // утечка логируется, ревью).
                        try {
                            bundle.recognizer.release()
                        } catch (e2: Exception) {
                            Timber.e(
                                e2,
                                "Не удалось освободить recognizer пучка ${bundle.info.modelId} — " +
                                    "нативная память может утечь"
                            )
                        }
                        val failedId = activeId
                        if (failedId != null) {
                            cache.remove(failedId)
                            activeId = null
                        }
                        publishLoadedUnlocked()
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
     * распознавания ([recognizerMutex]).
     */
    override suspend fun release() {
        recognizerMutex.withLock { releaseUnlocked() }
    }

    /**
     * Извлечь и освободить пучок модели [modelId] (удаление модели, issue #40).
     * Ждёт окончания активного распознавания под [recognizerMutex]; если пучок
     * был активным — сбрасывает [activeId] и публикует новый список
     * [loadedModels]. Отсутствующий в кэше id — no-op.
     */
    override suspend fun releaseModel(modelId: String) {
        recognizerMutex.withLock {
            cache.remove(modelId)?.let { bundle ->
                bundle.release()
                Timber.d("sherpa: пучок удалённой модели $modelId освобождён")
            }
            if (activeId == modelId) {
                activeId = null
            }
            publishLoadedUnlocked()
        }
    }

    /**
     * Непосредственная разгрузка всех пучков. Вызывать только под
     * [recognizerMutex], чтобы не пересекаться с активным decode.
     */
    private fun releaseUnlocked() {
        cache.clear().forEach { (_, bundle) -> bundle.release() }
        activeId = null
        _loadedModels.value = emptyList()
    }

    /**
     * Выгрузить все пучки кэша, кроме [keepId] (unload-режим при попадании
     * в кэш — движок держит только текущую модель). Вызывать только под
     * [recognizerMutex].
     */
    private fun unloadOtherBundlesUnlocked(keepId: String) {
        cache.keysNotIn(setOf(keepId)).forEach { staleId ->
            cache.remove(staleId)?.release()
            Timber.d("sherpa: выгружена модель другого направления $staleId")
        }
    }

    /**
     * Опубликовать список загруженных моделей (активная — первой).
     * Вызывать только под [recognizerMutex].
     */
    private fun publishLoadedUnlocked() {
        val active = activeId
        _loadedModels.value = cache.all()
            .sortedByDescending { (id, _) -> id == active }
            .map { (_, bundle) -> bundle.info }
    }

    /**
     * Метаданные модели (тип и пути к файлам).
     */
    private data class ModelInfo(
        val type: String,
        val tokensPath: String,
        val encoderPath: String?,
        val decoderPath: String?,
        val joinerPath: String?,
        val nemoModelPath: String?
    )

    /**
     * Определить тип модели по файлам каталога.
     *
     * Допущение: эвристика по именам ONNX-файлов (`encoder`/`decoder`/`joiner`,
     * префикс `model`) рассчитана на канонические имена sherpa-onnx
     * (`*.encoder.onnx` / `*.decoder.onnx` / `*.joiner.onnx`, NeMo — `model*.onnx`).
     * Она хрупкая: ONNX-файлы с произвольными («прочими») именами могут быть
     * классифицированы иначе или не распознаны вовсе. Состав файлов в пучках
     * каталога зафиксирован манифестом (models/manifest.json), для импортируемых
     * вручную наборов с нестандартными именами классификация не гарантируется.
     */
    private fun detectModel(dir: File): ModelInfo? {
        val files = dir.listFiles() ?: return null
        val onnxFiles = files.filter { it.isFile && it.name.endsWith(".onnx") }
        val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" }
            ?: return null

        fun byContains(part: String): String? =
            onnxFiles.firstOrNull { it.name.lowercase().contains(part) }?.absolutePath

        val joiner = byContains("joiner")
        val encoder = byContains("encoder")
        val decoder = byContains("decoder")

        return when {
            joiner != null && encoder != null && decoder != null -> ModelInfo(
                type = TYPE_TRANSDUCER,
                tokensPath = tokens.absolutePath,
                encoderPath = encoder,
                decoderPath = decoder,
                joinerPath = joiner,
                nemoModelPath = null
            )
            encoder != null && decoder != null -> ModelInfo(
                type = TYPE_WHISPER,
                tokensPath = tokens.absolutePath,
                encoderPath = encoder,
                decoderPath = decoder,
                joinerPath = null,
                nemoModelPath = null
            )
            else -> {
                val model = onnxFiles.firstOrNull { it.name.lowercase().startsWith("model") }
                if (model != null) {
                    ModelInfo(
                        type = TYPE_NEMO_CTC,
                        tokensPath = tokens.absolutePath,
                        encoderPath = null,
                        decoderPath = null,
                        joinerPath = null,
                        nemoModelPath = model.absolutePath
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Собрать OfflineRecognizer по метаданным модели.
     */
    private fun buildRecognizer(info: ModelInfo, language: String?): OfflineRecognizer {
        val modelConfig = when (info.type) {
            TYPE_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = info.encoderPath!!,
                    decoder = info.decoderPath!!,
                    joiner = info.joinerPath!!
                ),
                tokens = info.tokensPath
            )
            TYPE_WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = info.encoderPath!!,
                    decoder = info.decoderPath!!,
                    language = language ?: "auto",
                    task = "transcribe"
                ),
                tokens = info.tokensPath
            )
            else -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = info.nemoModelPath!!),
                tokens = info.tokensPath
            )
        }
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))
    }
}
