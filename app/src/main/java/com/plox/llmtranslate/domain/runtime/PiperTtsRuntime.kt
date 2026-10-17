package com.plox.llmtranslate.domain.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.plox.llmtranslate.domain.model.RuntimeError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
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
    }

    /** Пучок загруженной Piper-модели: нативный OfflineTts + путь каталога. */
    private class LoadedPiper(
        val tts: OfflineTts,
        val dirPath: String
    )

    /**
     * Защита нативного OfflineTts: синтез, загрузка и разгрузка
     * сериализуются — read [loaded] вне мьютекса допустим только для
     * проверки готовности, native-вызовы только под мьютексом.
     */
    private val engineMutex = Mutex()

    /** Текущий пучок; меняется только под [engineMutex]. */
    private var loaded: LoadedPiper? = null

    /** Флаг отмены активного озвучивания ([stop]); пишется из любого потока. */
    @Volatile
    private var stopRequested = false

    /** Активный AudioTrack для остановки озвучивания из [stop]; volatile — доступ из разных потоков. */
    @Volatile
    private var activeTrack: AudioTrack? = null

    /**
     * Загрузить Piper-модель из каталога [modelDir]. Повторная загрузка того же
     * пути — no-op; при смене каталога предыдущая модель освобождается.
     * @return null при успехе или [RuntimeError].
     */
    suspend fun load(modelDir: File): RuntimeError? = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val path = modelDir.absolutePath
            val current = loaded
            if (current != null && current.dirPath == path) return@withLock null

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
                loaded = LoadedPiper(tts, path)
                Timber.d("Piper TTS загружена: $path")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
        stopRequested = false

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
                    playPcm(audio.samples, audio.sampleRate)
                    null
                }
            }
        } catch (e: CancellationException) {
            // Отмена корутины озвучивания: гасим воспроизложение и всплываем выше.
            stop()
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
        stopRequested = true
        activeTrack?.let { track ->
            try {
                track.stop()
            } catch (e: Exception) {
                Timber.w(e, "Ошибка остановки AudioTrack")
            }
        }
    }

    /**
     * Разгрузить движок: сбрасывает активное озвучивание и асинхронно
     * освобождает нативный OfflineTts под мьютексом (ожидает окончания
     * активного синтеза — use-after-free исключён).
     */
    fun release() {
        stop()
        scope.launch(Dispatchers.IO) {
            engineMutex.withLock {
                loaded?.releaseNative()
                loaded = null
            }
        }
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
     * проверкой отмены корутины и флага [stopRequested] на каждом чанке.
     * @return null; ошибки записи — только в лог (обрыв не считается ошибкой синтеза).
     */
    private suspend fun playPcm(samples: FloatArray, sampleRate: Int) {
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
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Timber.e(e, "Не удалось создать AudioTrack для Piper TTS")
            return
        }
        activeTrack = track
        try {
            track.play()
            var offset = 0
            while (offset < samples.size) {
                if (stopRequested) break
                currentCoroutineContext().ensureActive()
                val count = minOf(PCM_CHUNK_SAMPLES, samples.size - offset)
                val pcm = toPcm16(samples, offset, count)
                // write блокирует пока буфер полон; stop() из stop() выводит
                // его раньше — цикл завершится по флагу на следующей итерации.
                val written = track.write(pcm, 0, count)
                if (written <= 0) {
                    Timber.w("AudioTrack.write вернул $written — озвучивание прервано")
                    break
                }
                offset += written
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
    private data class PiperLayout(
        val modelPath: String,
        val tokensPath: String,
        val espeakDataPath: String
    )

    /**
     * Найти в каталоге модели файлы: .onnx (приоритет model.onnx, иначе
     * крупнейший), tokens.txt (обязателен), espeak-ng-data/ (если есть).
     * @return null, если обязательные файлы не найдены.
     */
    private fun discoverLayout(dir: File): PiperLayout? {
        val files = dir.listFiles() ?: return null
        val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" }
            ?: return null
        val onnx = files.filter {
            it.isFile && it.name.endsWith(".onnx")
        }.maxByOrNull { it.length() } ?: return null
        val espeak = files.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
        return PiperLayout(
            modelPath = onnx.absolutePath,
            tokensPath = tokens.absolutePath,
            espeakDataPath = espeak?.absolutePath.orEmpty()
        )
    }
}
