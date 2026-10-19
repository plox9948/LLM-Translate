package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.data.native_engine.SlimtNative
import com.plox.llmtranslate.domain.ModelRetentionProvider
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Рантайм переводов на языковых паках Firefox Translations (Bergamot):
 * `model.*.intgemm.alphas.bin` + `lex.*.s2t.bin` + `vocab.*.spm`.
 * Инференс выполняет нативная библиотека slimt (тот же C++-движок, что
 * Firefox Translations и проект offline-translator): жадный декод с
 * повторным beam-проходом для ненадёжных предложений (two-pass robust
 * decoder внутри slimt), токенизация SentencePiece — на C++-стороне.
 *
 * Каталог файлов модели: каталог пачки из [InstalledModel.filePath]
 * (мультифайловая установка через SlimtInstaller).
 */
@Singleton
class SlimtRuntime @Inject constructor(
    private val retentionProvider: ModelRetentionProvider
) : TranslationEngine {

    companion object {
        private const val POLL_INTERVAL_MS = 10L
        private const val STATUS_PENDING = 0
        private const val STATUS_DONE = 1
        private const val STATUS_ERROR = 2
        private const val STATUS_CANCELLED = 3

        /**
         * Лимит дренажа после nativeCancel в NonCancellable: зависший воркер
         * slimt не должен держать мьютекс движка вечно (ревью #79).
         */
        private const val CANCEL_DRAIN_TIMEOUT_MS = 10_000L

        /** Имена файлов пачки с языковой меткой: model./lex.<nn>./vocab-. */
        private val MODEL_NAME_RE = Regex("^model\\.([a-z]{2,6})\\.")
        private val SHORTLIST_NAME_RE = Regex("^lex\\.(?:[0-9]+\\.)*([a-z]{2,6})\\.s2t\\.bin$")
        private val VOCAB_NAME_RE = Regex("^(?:srcvocab|trgvocab|vocab)\\.([a-z]{2,6})\\.spm$")
    }

    /**
     * Пучок модели slimt: нативный handle и метаданные для UI.
     * Пучки движка хранятся в LRU-кэше [engineCache].
     * Языковая пара модели — в [LoadedModelInfo.languages]; используется
     * для проверки направления в [translate] (ревью #79).
     */
    private class ModelBundle(
        val handle: Long,
        val info: LoadedModelInfo
    )

    /**
     * Scope для «зависшего» освобождения пучков (ревью повт. аудита):
     * nativeDestroy джойнит нативный воркер slimt и может не вернуться;
     * такое освобождение выносится из мьютексов переводчиков fire-and-forget
     * сюда (движок — синглтон процесса, scope живёт всё время процесса).
     */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Каркас LRU-кэша пучков: нативные модели защищены внутренним мьютексом —
     * запуск, опрос и разгрузка сериализованы, use-after-free нативного
     * slimt::Model исключён (деструктор slimt::Async дожидается воркера,
     * release() вызывается вне главного потока).
     */
    private val engineCache = EngineCacheScaffold<ModelBundle>(
        capacity = EngineCacheScaffold.DEFAULT_MODEL_CACHE_CAPACITY,
        retentionProvider = retentionProvider,
        logLabel = "slimt",
        releaseBundle = { bundle ->
            // nativeDestroy джойнит нативный воркер slimt и может не
            // вернуться: освобождение ЛЮБОГО пучка движка (release,
            // releaseModel, эвикция LRU и retention) всегда уходит в
            // detached-очередь вне sessionMutex — под мьютексом пучок
            // только изымается из кэша (ревью #83, унификация с
            // cancel-путём detachAllUnlocked/scheduleDetachedRelease).
            if (bundle.handle != 0L) {
                scheduleDetachedRelease(listOf(bundle))
            }
        },
        infoOf = { it.info },
    )

    /** Состояние загруженных моделей; пишется только под мьютексом [engineCache]. */
    override val loadedModels: StateFlow<List<LoadedModelInfo>> = engineCache.loadedModels

    /**
     * Загрузить пачку Firefox-модели (marian .bin + shortlist + словари).
     * При настройке keep другие направления остаются в LRU-кэше движка,
     * при unload — движок держит ТОЛЬКО текущую модель: чужие пучки
     * выгружаются и перед загрузкой, и при попадании в кэш (прежнее поведение).
     */
    override suspend fun ensureLoaded(modelInfo: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = installedModelPath(modelInfo)
                ?: return@withContext RuntimeError.ModelNotReady

            engineCache.sessionMutex.withLock {
                // Быстрый путь под мьютексом: пучок уже в кэше — делать
                // активным без повторной загрузки (смена направления, issue #36).
                if (engineCache.reuseCachedUnlocked(modelInfo.id)) {
                    return@withContext null
                }

                // Политика выгрузки при смене направления читается на каждую
                // загрузку — смена настройки применяется сразу.
                engineCache.applyRetentionUnlocked()

                val dir = File(path)
                val files = resolvePackageFiles(dir)
                if (files == null) {
                    Timber.e("Не найдены файлы slimt-пачки в $path")
                    return@withContext RuntimeError.LoadFailed(
                        "slimt",
                        "Не найдены файлы модели (нужны model.*.intgemm.alphas.bin, lex.*.s2t.bin, vocab.*.spm)"
                    )
                }
                // Языковая пара из имени файла модели (model.<src><tgt>.intgemm…)
                // сверяется с заявленными языками записи (ревью): подмешанная
                // пачка другой пары переводила бы молча чужой моделью.
                packLanguagePair(files.model.name)?.let { packPair ->
                    val declared = modelInfo.languages.toSet()
                    if (declared.isNotEmpty() && packPair != declared) {
                        Timber.e(
                            "slimt: языковая пара файлов пачки $packPair не совпадает " +
                                "с заявленной $declared в $path"
                        )
                        return@withContext RuntimeError.LoadFailed(
                            "slimt",
                            "Файлы пачки не соответствуют языкам модели"
                        )
                    }
                }

                try {
                    currentCoroutineContext().ensureActive()
                    val newHandle = SlimtNative.nativeCreate(
                        modelPath = files.model.absolutePath,
                        vocabPath = files.vocab.absolutePath,
                        shortlistPath = files.shortlist.absolutePath,
                        targetVocabPath = files.targetVocab?.absolutePath
                    )
                    if (newHandle == 0L) {
                        return@withContext RuntimeError.LoadFailed(
                            "slimt", "Не удалось создать модель slimt"
                        )
                    }
                    engineCache.putBundleUnlocked(
                        modelInfo.id,
                        ModelBundle(
                            handle = newHandle,
                            info = translationLoadedInfo(modelInfo)
                        )
                    )
                    engineCache.publishActiveUnlocked(modelInfo.id)
                    Timber.d("slimt: модель ${modelInfo.id} загружена (mmap)")
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось загрузить slimt-модель $path")
                    RuntimeError.LoadFailed("slimt", e.message ?: "Неизвестная ошибка")
                } catch (e: Throwable) {
                    // UnsatisfiedLinkError / ExceptionInInitializerError:
                    // System.loadLibrary в init SlimtNative бросает Error, а не
                    // Exception — без этой ветки он ронял бы процесс вместо
                    // явной LoadFailed (ревью #79). CancellationException
                    // обработан выше и не глотается.
                    Timber.e(e, "Критическая ошибка нативного слоя slimt при загрузке $path")
                    RuntimeError.LoadFailed("slimt", e.message ?: "Ошибка нативной библиотеки")
                }
            }
        }

    /**
     * Перевести текст через slimt (Blocking-фронтенд с атомарным флагом
     * отмены; опрос с границами отмены корутины между итерациями).
     * Инференс идёт по активному пучку кэша; пара [from]→[to] сверяется с
     * направлением активного пучка — при несовпадении (смена направления
     * между ensureLoaded и translate) [RuntimeError.DirectionMismatch], а не
     * перевод чужой моделью; при отсутствии активного — [RuntimeError.ModelNotSelected].
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            engineCache.sessionMutex.withLock {
                val bundle = engineCache.activeBundle()
                if (bundle == null || bundle.handle == 0L) {
                    return@withContext Result.failure(RuntimeError.ModelNotSelected)
                }
                // Проверяем, что активный пучок загружен для запрошенного
                // направления (ревью #79): мьютекс сериализует операции, но
                // другой вызов между ensureLoaded и translate мог сменить его.
                bundle.info.directionMismatch("slimt", from, to)?.let {
                    return@withContext Result.failure(it)
                }
                val handle = bundle.handle
                try {
                    SlimtNative.nativeStart(handle, text)
                    var outcome: Result<String>? = null
                    while (outcome == null) {
                        currentCoroutineContext().ensureActive()
                        when (val status = SlimtNative.nativePoll(handle)) {
                            STATUS_PENDING -> delay(POLL_INTERVAL_MS)

                            STATUS_DONE -> {
                                val result = SlimtNative.nativeTakeResult(handle)
                                    ?: return@withContext Result.failure(
                                        RuntimeError.InferenceFailed("slimt", "Не удалось прочитать результат")
                                    )
                                outcome = Result.success(result)
                            }

                            STATUS_ERROR -> {
                                val reason = SlimtNative.nativeTakeError(handle)
                                    ?: "Ошибка декодирования без сообщения"
                                outcome = Result.failure(
                                    RuntimeError.InferenceFailed("slimt", reason)
                                )
                            }

                            STATUS_CANCELLED -> {
                                outcome = Result.failure(
                                    RuntimeError.InferenceFailed("slimt", "Перевод отменён")
                                )
                            }

                            else -> {
                                // Неизвестный статус воркера — не PENDING:
                                // бесконечный poll на испорченном статусе скрыл бы
                                // сбой нативного слоя (ревью).
                                Timber.e("slimt: неизвестный статус nativePoll: $status")
                                outcome = Result.failure(
                                    RuntimeError.InferenceFailed(
                                        "slimt", "Неизвестный статус нативного воркера: $status"
                                    )
                                )
                            }
                        }
                    }
                    outcome
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        SlimtNative.nativeCancel(handle)
                        // С-drain до терминального статуса: воркер slimt
                        // прерывает пакет по флагу отмены; иначе следующий
                        // nativeStart упрётся в «already running». Ограничен
                        // таймаутом: зависший нативный воркер не должен
                        // удерживать мьютекс движка вечно (ревью #79).
                        val deadline = System.currentTimeMillis() + CANCEL_DRAIN_TIMEOUT_MS
                        var drainTimedOut = false
                        while (SlimtNative.nativePoll(handle) == STATUS_PENDING) {
                            if (System.currentTimeMillis() >= deadline) {
                                drainTimedOut = true
                                Timber.w(
                                    "slimt: отмена не завершилась за ${CANCEL_DRAIN_TIMEOUT_MS} мс — " +
                                        "дренаж прерван, следующий nativeStart может увидеть «already running»"
                                )
                                break
                            }
                            delay(POLL_INTERVAL_MS)
                        }
                        if (drainTimedOut) {
                            // Прерванный дренаж оставляет пучок «stale-running»:
                            // каждый последующий nativeStart давал бы ошибку, и
                            // движок оставался бы сломанным до общей разгрузки
                            // (ревью). Выгружаем пучки из кэша, НО nativeDestroy
                            // джойнит зависший воркер — освобождение под
                            // sessionMutex каскадно блокировало translate/mgr
                            // (цепочка importMutex→runtimeMutex, ревью повт.
                            // аудита). Поэтому под мьютексом пучки только
                            // ИЗЫМАЮТСЯ (ensureLoaded их больше не переиспользует,
                            // повторные release()/releaseModel — no-op), а сам
                            // nativeDestroy уходит fire-and-forget в собственный
                            // scope движка, где и дожидается зависший воркер.
                            Timber.w(
                                "slimt: пучок после прерванного дренажа изъят из кэша — " +
                                    "освобождение вне мьютекса (fire-and-forget)"
                            )
                            scheduleDetachedRelease(engineCache.detachAllUnlocked())
                        }
                    }
                    Timber.d("slimt: перевод отменён пользователем")
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "slimt: ошибка запуска перевода")
                    Result.failure(
                        RuntimeError.InferenceFailed("slimt", e.message ?: "Ошибка инференса")
                    )
                }
            }
        }

    /**
     * Разгрузить ВСЕ удерживаемые движком модели. Деструктор slimt::Async
     * дожидается воркера — вызов вне главного потока. Под [engineCache]
     * пучки только изымаются из кэша, [SlimtNative.nativeDestroy] — всегда
     * через detached-очередь вне мьютекса (ревью #83): зависший деструктор
     * не замораживает sessionMutex навсегда.
     */
    override suspend fun release() = engineCache.release()

    /**
     * Очередь пучков, изъятых из кэша, но ещё не уничтоженных nativeDestroy'ом
     * (ревью раунда 3): при зависшем воркере slimt освобождение неограниченно
     * накапливало fire-and-forget корутины и потоки. теперь один воркер на
     * [detachedWorkerActive]; новые пучки до его завершения просто дожидаются
     * в очереди (память посчитать утечкой нельзя до конца процесса, но
     * потоки не копятся).
     */
    private val detachedQueue = ArrayDeque<ModelBundle>()

    /** Есть ли незавершённое отложенное освобождение (запуск воркера однократен). */
    private val detachedWorkerActive = AtomicBoolean(false)

    /**
     * Освободить пучки, изъятые из кэша вне общего мьютекса
     * ([EngineCacheScaffold.detachAllUnlocked]) — в единственный воркер
     * отложенного освобождения на [releaseScope] (ревью повт. аудита).
     * nativeDestroy (деструктор slimt::Async) дожидается зависшего воркера
     * и может не вернуться вовсе; блокировать им translate/mgr недопустимо,
     * а отменять сам деструктор нативный слой не умеет — поэтому без
     * withTimeoutOrNull. Повторный вызов, пока предыдущее освобождение
     * висит, новых потоков не поднимает: пучки кладутся в [detachedQueue]
     * и будут уничтожены тем же воркером по завершении текущего
     * (ревью раунда 3). Уничтожение каждого handle ровно единожды: пучки
     * изъяты из кэша, повторные release()/releaseModel на них не сработают
     * (use-after-free исключён).
     */
    private fun scheduleDetachedRelease(detached: List<ModelBundle>) {
        if (detached.isEmpty()) return
        synchronized(detachedQueue) { detachedQueue.addAll(detached) }
        if (!detachedWorkerActive.compareAndSet(false, true)) return
        releaseScope.launch(NonCancellable) {
            while (true) {
                val bundle = synchronized(detachedQueue) {
                    val head = detachedQueue.poll()
                    // Флаг снимается в том же замке, что и опустошение
                    // очереди: конкурентный scheduleDetachedRelease либо
                    // застанет активного воркера (пучок дождётся этого
                    // цикла), либо добавит после сброса и сам поднимет
                    // новый воркер.
                    if (head == null) detachedWorkerActive.set(false)
                    head
                } ?: break
                if (bundle.handle != 0L) {
                    try {
                        SlimtNative.nativeDestroy(bundle.handle)
                    } catch (e: Exception) {
                        Timber.e(e, "slimt: ошибка отложенного nativeDestroy")
                    }
                }
            }
        }
    }

    /**
     * Извлечь и уничтожить пучок модели [modelId] (удаление модели, issue #40).
     * Ждёт окончания активного перевода под мьютексом [engineCache]; если
     * пучок был активным — сбрасывается активный id и публикуется новый
     * список [loadedModels]. Отсутствующий в кэше id — no-op. Как и
     * [release], nativeDestroy выполняется вне мьютекса через
     * detached-очередь (ревью #83).
     */
    override suspend fun releaseModel(modelId: String) = engineCache.releaseModel(modelId)

    /**
     * Файлы пачки Bergamot: модель, shortlist, словарь; для двух-словарных
     * паков (srcvocab/trgvocab) — второй словарь отдельно.
     */
    internal data class PackageFiles(
        val model: File,
        val vocab: File,
        val shortlist: File,
        val targetVocab: File?
    )

    internal fun resolvePackageFiles(dir: File): PackageFiles? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val model = files.firstOrNull { it.name.endsWith("alphas.bin") } ?: return null
        val shortlist = files.firstOrNull { it.name.endsWith("s2t.bin") } ?: return null
        val sharedVocab = files.firstOrNull { it.name.startsWith("vocab") && it.name.endsWith(".spm") }
        val srcVocab = files.firstOrNull { it.name.startsWith("srcvocab") && it.name.endsWith(".spm") }
        val trgVocab = files.firstOrNull { it.name.startsWith("trgvocab") && it.name.endsWith(".spm") }
        val vocab = srcVocab ?: sharedVocab ?: return null
        val targetVocab = if (srcVocab != null) trgVocab else null
        // Сверка языковой пары из имён (ревью): файлы из разных пачек,
        // подмешанные в один каталог (model.ruen + vocab.enzh), загружались
        // как «корректная» модель и давали мусор перевода — метки всех
        // извлекаемых файлов обязаны описывать одну и ту же пару
        // (двусторонние паки ruen/enru эквивалентны).
        val pairs = listOfNotNull(
            packLanguagePair(model.name),
            packLanguagePair(shortlist.name),
            packLanguagePair(vocab.name),
            targetVocab?.name?.let { packLanguagePair(it) }
        ).distinct()
        if (pairs.size >= 2) {
            Timber.e("slimt: файлы пачки из разных языковых пар: $pairs")
            return null
        }
        return PackageFiles(
            model = model,
            vocab = vocab,
            shortlist = shortlist,
            targetVocab = targetVocab
        )
    }

    /**
     * Языковая пара пачки Bergamot из метки имени файла: `model.enru.intgemm…`,
     * `lex.50.50.enru.s2t.bin`, `vocab.enru.spm` → `{en, ru}` (порядок метки
     * не значим — паки двусторонние). null, если метка не извлекается или
     * не делится на два кода языка — проверяющий трактует null как
     * «сверка невозможна, не блокируем».
     */
    internal fun packLanguagePair(fileName: String): Set<String>? {
        val tag = MODEL_NAME_RE.find(fileName)?.groupValues?.get(1)
            ?: SHORTLIST_NAME_RE.find(fileName)?.groupValues?.get(1)
            ?: VOCAB_NAME_RE.find(fileName)?.groupValues?.get(1)
            ?: return null
        if (tag.length % 2 != 0) return null
        val half = tag.length / 2
        if (half !in 2..3) return null
        return setOf(tag.take(half), tag.drop(half))
    }
}
