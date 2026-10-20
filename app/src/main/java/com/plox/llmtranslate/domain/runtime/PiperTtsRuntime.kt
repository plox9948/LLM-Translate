package com.plox.llmtranslate.domain.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм TTS на Piper/VITS через sherpa-onnx OfflineTts (issue #50).
 *
 * Каталог модели после распаковки tar.bz2 (плоский, ArchiveInstaller
 * подтягивает единственный подкаталог архива наверх):
 * - `<голос>.onnx` — модель VITS (ищется любой *.onnx, приоритет у model.onnx);
 * - `tokens.txt` — список токенов (обязателен);
 * - `espeak-ng-data/` — данные eSpeak NG для фонемизации (opus-mt-подобные
 *   piper-голосы используют его вместо локальных лексиконов).
 *
 * Синтез (`generate`) — блокирующий CPU-вызов на нативном движке, выполняется
 * на [Dispatchers.Default] под мьютексом; озвучивание — записи PCM-чанков в
 * AudioTrack (MODE_STREAM) того же фона. Повторный [load] того же каталога —
 * no-op (кэш одного пучка, модель TTS всегда одна активная).
 *
 * Защита нативного handle: [generate]/[load]/[release] сериализуются мьютексом —
 * release() дожидается окончания активного синтеза (use-after-free исключён).
 */
@Singleton
class PiperTtsRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        /** Пути конфигурации OfflineTts читаются с файла — AssetManager не нужен. */
        private const val NUM_THREADS = 2

        /** Разовый размер чанка PCM при записи в AudioTrack, отсчётов. */
        private const val PCM_CHUNK_SAMPLES = 4096

        /** Интервал опроса playbackHeadPosition при дренаже буфера, мс. */
        private const val DRAIN_POLL_MS = 20L

        /** Предельное время дренажа буфера перед остановкой трека, мс. */
        private const val DRAIN_TIMEOUT_MS = 5000L
    }

    /** Пучок загруженной Piper-модели: нативный OfflineTts + путь каталога + метаданные для UI. */
    private class LoadedPiper(
        val tts: OfflineTts,
        val dirPath: String,
        val info: LoadedModelInfo
    )

    /**
     * Защита нативного OfflineTts: синтез, загрузка и разгрузка
     * сериализуются — read [loaded] вне мьютекса допустим только для
     * проверки готовности, native-вызовы только под мьютексом.
     */
    private val engineMutex = Mutex()

    /**
     * Текущий пучок; меняется только под [engineMutex]. @Volatile — чтение
     * готовности ([speak], [releaseModel]) идёт вне мьютекса из других потоков:
     * без guaranteed-видимости (happens-before) готовность модели могла не
     * проявиться читающему потоку.
     */
    @Volatile
    private var loaded: LoadedPiper? = null

    private val _loadedModels = MutableStateFlow<List<LoadedModelInfo>>(emptyList())

    /**
     * Реактивное состояние: загруженная в ОЗУ TTS-модель (одна активная;
     * пуст список, если движок разгружен). Обновляется под [engineMutex]
     * при загрузке/разгрузке (issue #52).
     */
    val loadedModels: StateFlow<List<LoadedModelInfo>> = _loadedModels

    /**
     * Гейт поколений озвучивания (ревью #83): прежний boolean-флаг терялся
     * при конкурентных [stop]/[speak] — новая фраза сбрасывала флаг, а
     * отменённая прежняя выставляла его снова и молча убивала воспроизведение
     * новой фразы с «успешным» результатом. [speak] резервирует поколение до
     * старта, [stop] снимает текущее на момент вызова и все прежние, путь
     * отмены корутины фразы снимает только своё поколение
     * ([PiperStopGate.stopThrough], регрессия #83), [playPcm] проверяет своё
     * поколение — сброс флага новой фразой отсутствует.
     */
    private val stopGate = PiperStopGate()

    /** Активный AudioTrack для остановки озвучивания из [stop]; volatile — доступ из разных потоков. */
    @Volatile
    private var activeTrack: AudioTrack? = null

    /**
     * Поколение фразы, которой принадлежит активный трек (0 — трека нет):
     * гейт остановки трека на пути отмены (регрессия #83) — отменённая старая
     * фраза не гасит трек, уже принадлежащий новой.
     */
    @Volatile
    private var activeTrackGeneration: Int = 0

    /**
     * Загрузить Piper-модель из каталога [modelDir]. Повторная загрузка того же
     * пути — no-op; при смене каталога предыдущая модель освобождается.
     * [model] — запись установленной модели для метаданных карточки
     * «Загружено в память» (id, имя, оценка ОЗУ); без неё метаданные
     * выводятся из имени каталога (issue #52).
     * @return null при успехе или [RuntimeError].
     */
    suspend fun load(modelDir: File, model: InstalledModel? = null): RuntimeError? =
        withContext(Dispatchers.IO) {
            // playPcm выполняется под [engineMutex] всю фазу воспроизведения
            // (перенос вне мьютекса рискован для stop-семантики с activeTrack,
            // ревью). Чтобы смена каталога не ждала конца озвучки, гасим её
            // заранее: циклы playPcm проверяют снятие поколения [stopGate] на
            // каждом чанке/дренаже и освобождают мьютекс досрочно (тот же
            // путь, что [releaseModel]/[release]).
            // Ревью #83: сравнение по каноническому пути — один и тот же
            // каталог, адресованный разными записями (symlink, «..»), имел бы
            // разные absolutePath и ложно считался сменой модели.
            val path = canonicalPath(modelDir)
            val existing = loaded
            if (existing != null && existing.dirPath != path) {
                stop()
            }
            engineMutex.withLock {
                val current = loaded
                if (current != null && current.dirPath == path) {
                    // No-op повторной загрузки того же каталога: метаданные
                    // опубликованной карточки обновляем из переданной записи
                    // (имя/оценка ОЗУ/id могли измениться после edits каталога
                    // или перескачивания) и ре-публикуем список (ревью).
                    if (model != null) {
                        loaded = LoadedPiper(
                            tts = current.tts,
                            dirPath = path,
                            info = LoadedModelInfo(
                                modelId = model.id,
                                displayName = model.displayName,
                                category = ModelCategory.TTS,
                                ramRequiredMb = model.ramRequiredMb,
                                // Языки пучка в карточке «Загружено в память»
                                // (ревью #83, тот же класс, что починен у
                                // SherpaSttEngine).
                                languages = model.languages
                            )
                        )
                        publishLoadedUnlocked()
                    }
                    return@withLock null
                }

                val layout = discoverLayout(modelDir)
                    ?: return@withLock RuntimeError.LoadFailed(
                        "piper", "В каталоге модели нет .onnx или tokens.txt: $path"
                    )

                try {
                    current?.releaseNative()
                    val config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = layout.modelPath,
                                tokens = layout.tokensPath,
                                dataDir = layout.espeakDataPath
                            ),
                            numThreads = NUM_THREADS
                        )
                    )
                    // Файловая загрузка — AssetManager нативному конструктору не нужен.
                    val tts = OfflineTts(null, config)
                    loaded = LoadedPiper(
                        tts = tts,
                        dirPath = path,
                        info = LoadedModelInfo(
                            modelId = model?.id ?: modelDir.name,
                            displayName = model?.displayName ?: modelDir.name,
                            category = ModelCategory.TTS,
                            ramRequiredMb = model?.ramRequiredMb ?: 0,
                            // Языки пучка (ревью #83): у импортированных без
                            // объявленных языков — пусто (гейт directionMismatch
                            // для них пропускается).
                            languages = model?.languages ?: emptyList()
                        )
                    )
                    publishLoadedUnlocked()
                    Timber.d("Piper TTS загружена: $path")
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Старый пучок уже освобождён (releaseNative выше) — снимаем
                    // его и из состояния, чтобы «Загружено в память» не показывал
                    // несуществующую модель (issue #52).
                    loaded = null
                    publishLoadedUnlocked()
                    Timber.e(e, "Не удалось загрузить Piper-модель $path")
                    RuntimeError.LoadFailed("piper", e.message ?: "Неизвестная ошибка")
                }
            }
        }

    /**
     * Синтезировать и озвучить [text]. Возвращается по завершении
     * озвучивания (или после [stop]). Колбэк [onDone] — как convenience для
     * невисящих вызывающих экранов: приходит на главном потоке после
     * окончания воспроизведения (в том числе при отмене через [stop]);
     * при ошибке до старта воспроизведения не вызывается.
     * @param speed множитель скорости (1f — обычная).
     * @param sid индекс диктора (у piper multi-speaker голосов).
     * @return null при успехе (включая раннюю остановку) или [RuntimeError].
     */
    suspend fun speak(
        text: String,
        speed: Float = 1f,
        sid: Int = 0,
        onDone: (() -> Unit)? = null
    ): RuntimeError? {
        if (loaded == null) return RuntimeError.ModelNotReady
        // Ревью #83: поколение этой фразы резервируется до старта; stop()
        // снимет поколения, актуальные на момент вызова, а новая фраза
        // флаг не сбрасывает.
        val myGeneration = stopGate.reserve()

        var started = false
        val result = try {
            withContext(Dispatchers.Default) {
                engineMutex.withLock {
                    val bundle = loaded
                        ?: return@withLock RuntimeError.ModelNotReady
                    val audio = try {
                        // Нативный синтез не прерываем (секунды), отмена — на фазах вокруг.
                        currentCoroutineContext().ensureActive()
                        bundle.tts.generate(text, sid, speed)
                    } catch (e: Exception) {
                        Timber.e(e, "Ошибка синтеза Piper TTS")
                        return@withLock RuntimeError.InferenceFailed(
                            "piper", e.message ?: "Неизвестная ошибка"
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    started = true
                    playPcm(audio.samples, audio.sampleRate, myGeneration)
                    null
                }
            }
        } catch (e: CancellationException) {
            // Отмена корутины озвучивания (снятие старой фразы через
            // speakJob.cancel() в координаторе): снимаем ТОЛЬКО поколение этой
            // фразы и ранние — глобальный stopAll() из stop() снял бы и только
            // что зарезервированное поколение новой фразы, заглушив её
            // озвучку (регрессия #83). Глобальное снятие остаётся за явным
            // пользовательским stop()/release().
            stopGate.stopThrough(myGeneration)
            if (activeTrackGeneration == myGeneration) {
                stopActiveTrack()
            }
            throw e
        }
        if (started) {
            onDone?.let { callback -> scope.launch(Dispatchers.Main) { callback() } }
        }
        return result
    }

    /**
     * Остановить активное озвучивание (синтез до конца фразы не прерывается,
     * воспроизведение обрывается сразу).
     */
    fun stop() {
        // Явная остановка (пользователь/разгрузка/смена модели): снимает все
        // поколения вплоть до последнего зарезервированного (ревью #83).
        stopGate.stopAll()
        activeTrackGeneration = 0
        stopActiveTrack()
    }

    /** Остановить активный AudioTrack (ошибки — только в лог). */
    private fun stopActiveTrack() {
        activeTrack?.let { track ->
            try {
                track.stop()
            } catch (e: Exception) {
                Timber.w(e, "Ошибка остановки AudioTrack")
            }
        }
    }

    /**
     * Разгрузить движок: гасит активное озвучивание и освобождает нативный
     * OfflineTts под [engineMutex] (ожидает окончания активного синтеза —
     * use-after-free исключён).
     *
     * Ревью #83: путь suspend и сериализован тем же [engineMutex] с
     * параллельными [load]/[speak]. Прежний fire-and-forget `scope.launch`
     * мог не диспатчиться до конкурентного [load] — тогда release, забрав
     * мьютекс ПОСЛЕ загрузки, освобождал бы свежий пучок вместо старого.
     * Остановку озвучивания делаем синхронно до ожидания мьютекса: тишина
     * наступает сразу, даже когда синтез держит мьютекс.
     */
    suspend fun release() {
        stop()
        engineMutex.withLock {
            loaded?.releaseNative()
            loaded = null
            publishLoadedUnlocked()
        }
    }

    /**
     * Освободить пучок модели [modelId] (удаление/перескачивание Piper-модели,
     * ревью #51): без этого запись в движке с тем же id продолжала
     * «показывать» модель загруженной, а повторная загрузка того же пути
     * молча оставляла stale-нативный OfflineTts по уже удалённым файлам
     * ([load] — no-op при совпадении каталога). Гасит активное озвучивание
     * и освобождает native под [engineMutex] (ожидает окончания синтеза —
     * use-after-free исключён); после освобождения следующий [load] поднимает
     * свежие файлы. Чужой или отсутствующий id — no-op.
     */
    suspend fun releaseModel(modelId: String) {
        if (loaded?.info?.modelId == modelId) {
            stop()
        }
        engineMutex.withLock {
            val bundle = loaded ?: return@withLock
            if (bundle.info.modelId != modelId) return@withLock
            bundle.releaseNative()
            loaded = null
            publishLoadedUnlocked()
            Timber.d("Piper TTS: пучок удалённой модели $modelId освобождён")
        }
    }

    /**
     * Опубликовать состояние загруженной модели (одна активная или пусто).
     * Вызывать только под [engineMutex].
     */
    private fun publishLoadedUnlocked() {
        _loadedModels.value = loaded?.let { listOf(it.info) } ?: emptyList()
    }

    /**
     * Канонический путь каталога модели (ревью #83); при сбое [File.canonicalPath]
     * (IO) — [File.absolutePath] как резервный вариант сравнения.
     */
    private fun canonicalPath(file: File): String = try {
        file.canonicalPath
    } catch (e: IOException) {
        Timber.w(e, "Не удалось получить canonicalPath, сравнение по absolutePath: ${file.path}")
        file.absolutePath
    }

    /** Освободить нативный OfflineTts пучка (ошибки — только в лог). */
    private fun LoadedPiper.releaseNative() {
        try {
            tts.release()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при разгрузке Piper TTS")
        }
    }

    /**
     * Записать PCM-чанки в AudioTrack (MODE_STREAM, 16-бит, моно) с
     * проверкой отмены корутины и снятия поколения фразы ([stopGate]) на каждом
     * чанке.
     *
     * После нормального завершения записи выполняется дренаж буфера (issue #51):
     * трек остаётся в PLAYING, ожидание до [DRAIN_TIMEOUT_MS] с опросом каждые
     * [DRAIN_POLL_MS] мс. Критерии полного вывода: головка воспроизведения
     * догоняет записанные кадры ([AudioTrack.getPlaybackHeadPosition] >=
     * записано) ИЛИ трек сам переходит из PLAYING в STOPPED (в MODE_STREAM при
     * исчерпании записанных данных) — иначе последние ~чанка (~180 мс)
     * «проглатываются» stop()/release() в finally. Снятое поколение фразы
     * ([stopGate], ревью #83) или истечение таймаута — мгновенный выход из
     * цикла. При IllegalStateException у getter'а дренаж пропускается (защита
     * от зависания). Снятое поколение (или отмена корутины, или written<=0 в
     * цикле записи) пропускает дренаж: [stop] уже останавливает трек
     * немедленно.
     * @param myGeneration поколение фразы, резервированное в [speak].
     * @return null; ошибки записи — только в лог (обрыв не считается ошибкой синтеза).
     */
    private suspend fun playPcm(samples: FloatArray, sampleRate: Int, myGeneration: Int) {
        if (samples.isEmpty()) return
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuffer > 0) {
            maxOf(minBuffer, PCM_CHUNK_SAMPLES * 2)
        } else {
            PCM_CHUNK_SAMPLES * 8
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                // MODE_STREAM (он же transfer mode по умолчанию) — обязателен для
                // playbackHeadPosition при дренаже (иначе getter бросает ISE).
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Timber.e(e, "Не удалось создать AudioTrack для Piper TTS")
            return
        }
        activeTrackGeneration = myGeneration
        activeTrack = track
        try {
            track.play()
            var offset = 0
            var framesWritten = 0L
            var writeAborted = false
            while (offset < samples.size) {
                if (stopGate.isStopped(myGeneration)) break
                currentCoroutineContext().ensureActive()
                val count = minOf(PCM_CHUNK_SAMPLES, samples.size - offset)
                val pcm = toPcm16(samples, offset, count)
                // write блокирует пока буфер полон; stop() гасит трек и снимает
                // поколение — цикл завершится на следующей итерации.
                val written = track.write(pcm, 0, count)
                if (written <= 0) {
                    Timber.w("AudioTrack.write вернул $written — озвучивание прервано")
                    writeAborted = true
                    break
                }
                offset += written
                framesWritten += written.toLong()
            }
            // Дренаж буфера перед остановкой (issue #51): без него последние
            // записанные кадры обрезаются stop()/release() в finally.
            // pause() для дренажа НЕ используется: на части устройств (проверено
            // на OnePlus A3003, OxygenOS 9) в PAUSED аппаратное потребление
            // останавливается — playbackHeadPosition замирает и хвост не
            // выводится. Трек остаётся в PLAYING; полный вывод определяется по
            // head >= framesWritten или переходу трека из PLAYING в STOPPED
            // (в MODE_STREAM происходит автоматически при исчерпании данных).
            if (!stopGate.isStopped(myGeneration) && !writeAborted && framesWritten > 0) {
                try {
                    val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (stopGate.isStopped(myGeneration)) break
                        val head = track.playbackHeadPosition.toLong()
                        // Полный вывод: головка догнала записанные кадры ИЛИ
                        // трек сам перешёл из PLAYING в STOPPED (в MODE_STREAM
                        // происходит автоматически при исчерпании данных).
                        if (head >= framesWritten ||
                            track.playState != AudioTrack.PLAYSTATE_PLAYING
                        ) {
                            Timber.d("Piper TTS: выведено %d фреймов из %d", head, framesWritten)
                            break
                        }
                        if (System.currentTimeMillis() >= deadline) {
                            Timber.w(
                                "Piper TTS: дренаж буфера по таймауту — выведено %d фреймов из %d",
                                head, framesWritten
                            )
                            break
                        }
                        delay(DRAIN_POLL_MS)
                    }
                } catch (e: IllegalStateException) {
                    Timber.w(e, "Piper TTS: playbackHeadPosition недоступен — дренаж пропущен")
                }
            }
        } finally {
            activeTrack = null
            try {
                track.stop()
            } catch (e: Exception) {
                // Остановка уже выполненного/остановленного трека — штатно.
            }
            track.release()
        }
    }

    /** Конвертировать фрагмент float-отсчётов [-1..1] в PCM-16 с клиппингом. */
    private fun toPcm16(samples: FloatArray, from: Int, count: Int): ShortArray {
        val pcm = ShortArray(count)
        for (i in 0 until count) {
            val sample = samples[from + i]
            val clipped = if (sample.isNaN()) 0f else sample.coerceIn(-1f, 1f)
            pcm[i] = (clipped * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm
    }

    /**
     * Пути файлов Piper-каталога модели.
     */
    internal data class PiperLayout(
        val modelPath: String,
        val tokensPath: String,
        val espeakDataPath: String
    )

    /**
     * Найти в каталоге модели файлы: .onnx (приоритет файлу model.onnx —
     * каноническое имя piper-экспортов, сравнение без учёта регистра, —
     * иначе крупнейший по размеру), tokens.txt (обязателен),
     * espeak-ng-data/ (если есть).
     * @return null, если обязательные файлы не найдены.
     */
    internal fun discoverLayout(dir: File): PiperLayout? {
        val files = dir.listFiles() ?: return null
        val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" }
            ?: return null
        val onnxFiles = files.filter {
            it.isFile && it.name.endsWith(".onnx", ignoreCase = true)
        }
        // Приоритет канонического model.onnx (KDoc обещал его с issue #50,
        // ревью раунд 3): крупный попутный *.onnx (напр. словарь/резервная
        // копия) не должен вытеснять основную модель по размеру.
        val onnx = onnxFiles.firstOrNull { it.name.equals("model.onnx", ignoreCase = true) }
            ?: onnxFiles.maxByOrNull { it.length() }
            ?: return null
        val espeak = files.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
        return PiperLayout(
            modelPath = onnx.absolutePath,
            tokensPath = tokens.absolutePath,
            espeakDataPath = espeak?.absolutePath.orEmpty()
        )
    }
}

/**
 * Гейт поколений озвучивания Piper TTS (ревью #83, выделен для unit-теста).
 *
 * Проблема прежней реализации: глобальный boolean-флаг отмены — новая фраза
 * сбрасывала его при старте, а завершающаяся/отменённая прежняя фраза
 * (путь catch в speak, конкурентный stop из UI) выставляла снова:
 * воспроизведение новой фразы молча не запускалось, возвращая «успех».
 *
 * Решение: монотонные счётчики поколений. [reserve] выдаёт номер фразы перед
 * стартом; [stopAll] отмечает снятыми все поколения, актуальные на момент
 * вызова (включая только что зарезервированное — осознанная семантика «стоп
 * сейчас играющего», применяется явным пользовательским stop());
 * [stopThrough] снимает только своё поколение и ранние — путь отмены старой
 * фразы без затрагивания новой (регрессия #83: stopAll на отмене глушил фразу,
 * зарезервированную после отмены); [isStopped] проверяет конкретное поколение
 * фразы, поэтому отмена старой фразы не затрагивает новую, резервированную
 * позже stop-вызова.
 */
internal class PiperStopGate {

    /** Номер последнего зарезервированного озвучивания. */
    private val playSeq = AtomicInteger(0)

    /** Максимальное снятое поколение: фразы с номером <= нему остановлены. */
    private val stoppedSeq = AtomicInteger(0)

    /** Зарезервировать поколение нового озвучивания. */
    fun reserve(): Int = playSeq.incrementAndGet()

    /** Снять все поколения, актуальные на текущий момент. */
    fun stopAll() {
        stoppedSeq.set(playSeq.get())
    }

    /**
     * Снять поколение [generation] и все ранние (отмена собственной фразы):
     * поколения, зарезервированные позже него — активное озвучивание новой
     * фразы — остаются незадетыми.
     */
    fun stopThrough(generation: Int) {
        stoppedSeq.updateAndGet { if (it >= generation) it else generation }
    }

    /** Снято ли поколение [myGeneration] (или его старт был бы остановлен). */
    fun isStopped(myGeneration: Int): Boolean = stoppedSeq.get() >= myGeneration
}
