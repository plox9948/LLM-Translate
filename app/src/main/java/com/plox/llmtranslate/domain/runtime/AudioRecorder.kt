package com.plox.llmtranslate.domain.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Формирование WAV-файла из сырых PCM-данных.
 * Чистая логика, вынесена для unit-тестов.
 */
object WavWriter {

    /**
     * Собрать WAV-заголовок (44 байта, RIFF/WAVE).
     * @param pcmSize размер PCM-данных в байтах
     * @param sampleRate частота дискретизации
     * @param channels число каналов (1 — mono)
     * @param bitsPerSample разрядность (16)
     */
    fun buildHeader(pcmSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        // "RIFF"
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, 36 + pcmSize)

        // "WAVE"
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // "fmt "
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1) // PCM
        writeShortLE(header, 22, channels)
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, blockAlign)
        writeShortLE(header, 34, bitsPerSample)

        // "data"
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, pcmSize)

        return header
    }

    /**
     * Записать WAV-файл (заголовок + PCM).
     * @return true при успехе.
     */
    fun writeWav(
        file: File,
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): Boolean {
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                out.write(buildHeader(pcm.size, sampleRate, channels, bitsPerSample))
                out.write(pcm)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Long) {
        // 32-битные поля WAV: при выходе за знаковое поле — guard (пишем
        // максимум с предупреждением). При потолке записи переполнения нет,
        // но размеры считается корректно в Long на будущее (issue #68).
        val v = if (value > MAX_WAV_FIELD_BYTES) {
            Timber.w("WAV: размер $value не помещается в 32-битное поле — пишем $MAX_WAV_FIELD_BYTES")
            MAX_WAV_FIELD_BYTES
        } else {
            value
        }
        bytes[offset] = (v and 0xFF).toByte()
        bytes[offset + 1] = ((v shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((v shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /** Поле long → 32-битное LE-поле WAV (см. guard в [writeIntLE]). */
    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) =
        writeIntLE(bytes, offset, value.toLong())

    private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    /**
     * Проставить фактические размеры в поля RIFF/data ранее записанного
     * потокового заголовка (стриминговая запись WAV в файл, issue #68).
     * Размеры считаются в Long; переполнение 32-битного поля ограничивается
     * максимумом с предупреждением.
     * @param pcmBytes суммарный размер PCM-данных после заголовка.
     * @return true при успехе.
     */
    fun patchSizes(file: File, pcmBytes: Long): Boolean = try {
        RandomAccessFile(file, "rw").use { raf ->
            val riff = ByteArray(4)
            writeIntLE(riff, 0, 36L + pcmBytes)
            raf.seek(4)
            raf.write(riff)
            val data = ByteArray(4)
            writeIntLE(data, 0, pcmBytes)
            raf.seek(40)
            raf.write(data)
        }
        true
    } catch (e: Exception) {
        Timber.e(e, "Не удалось обновить размеры WAV-заголовка: ${file.path}")
        false
    }

}

/** Максимум, записываемый в 32-битное знаковое WAV-поле при переполнении. */
private const val MAX_WAV_FIELD_BYTES = 0x7FFFFFFFL

/**
 * Стриминговый приёмник PCM в WAV-файл (issue #68): 44-байтный заголовок
 * с нулевыми размерами пишется сразу, PCM-чанки дописываются по мере
 * чтения с микрофона, при остановке размеры проставляются на месте через
 * [WavWriter.patchSizes]. Накопление всего PCM в памяти отсутствует —
 * вместо двойного пика памяти (буфер + toByteArray) только файловый поток.
 * Не потокобезопасен: вызывающий сериализует доступ своим замком (тот же
 * порядок, что ранее обеспечивал [synchronized] вокруг ByteArrayOutputStream).
 */
class WavSink(
    val file: File,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int
) : AutoCloseable {
    private val stream = BufferedOutputStream(FileOutputStream(file), STREAM_BUFFER_BYTES)
    private var pcmBytes = 0L

    /** Сколько PCM-байт уже дописано в файл. */
    val pcmSize: Long
        get() = pcmBytes

    init {
        stream.write(WavWriter.buildHeader(0, sampleRate, channels, bitsPerSample))
    }

    /** Дописать чанк PCM-данных. */
    fun write(data: ByteArray, offset: Int, length: Int) {
        stream.write(data, offset, length)
        pcmBytes += length
    }

    /**
     * Закрыть поток и проставить фактические размеры в заголовок.
     * @return true при успехе.
     */
    fun closeAndPatch(): Boolean = try {
        stream.close()
        WavWriter.patchSizes(file, pcmBytes)
    } catch (e: Exception) {
        Timber.e(e, "Не удалось завершить потоковый WAV: ${file.path}")
        false
    }

    /** Закрыть поток и удалить файл (отмена/ошибка записи). */
    fun discard() {
        try {
            stream.close()
        } catch (e: Exception) {
            Timber.w(e, "Не удалось закрыть поток записи: ${file.path}")
        }
        if (!file.delete()) {
            Timber.w("Не удалось удалить незавершённый WAV: ${file.path}")
        }
    }

    override fun close() {
        closeAndPatch()
    }

    private companion object {
        /** Буфер потока: мелкие чанки AudioRecord склеиваются в крупные записи на диск. */
        const val STREAM_BUFFER_BYTES = 32 * 1024
    }
}

/**
 * Рекордер аудио для STT: захват микрофона (AudioRecord) и запись WAV 16 kHz mono 16-bit.
 * Формат совместим с whisper.cpp.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16

        /**
         * Жёсткий потолок длительности записи, мс (issue #68): максимум
         * осмысленного STT-сценария (3 минуты). При достижении цикл чтения
         * прекращает приём данных, ViewModel выполняет авто-остановку с
         * корректным завершением (сохранение WAV и распознавание).
         */
        const val MAX_RECORDING_MS = 180_000L

        /** PCM-байт, соответствующих потолку [MAX_RECORDING_MS]. */
        val MAX_PCM_BYTES: Long =
            MAX_RECORDING_MS / 1000L * (SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8).toLong()
    }

    /**
     * Флаг активной записи: пишет рекордер (start/cancel/stop), читают цикл
     * чтения (другой поток) и UI. Бэкинг — [recordingFlow] (ревью раунд 2):
     * StateFlow даёт и междупоточную видимость (happens-before), и реактивную
     * подписку ViewModel, чтобы индикатор не залипал при внешнем cancel().
     */
    private val _recordingFlow = MutableStateFlow(false)

    /** Реактивное состояние записи для UI (true — идёт запись с микрофона). */
    val recordingFlow: StateFlow<Boolean> = _recordingFlow

    /**
     * Истёкшая длительность текущей записи, мс (issue #68): обновляется
     * циклом чтения ~раз в секунду, обнуляется стартом; UI показывает
     * таймер в индикаторе записи.
     */
    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs

    /** Активна ли запись сейчас. */
    val isRecording: Boolean
        get() = _recordingFlow.value

    // Сцена записи (ревью раунд 4): на неё входят из разных потоков —
    // startRecording()/stopRecording() из UI (Main), cancel() из разгрузки
    // рантаймов (IO app-scope). Мьютекс sceneLock фиксирует вход: перевод
    // флага записи, снятие поколения и захват целевых объектов под замком;
    // долгоживущие cleanup-участки (join цикла чтения, stop/release,
    // запись WAV) выполняются вне замка. Поля при этом @Volatile — читаются
    // и точечно правятся вне замка в cleanup под проверками идентичности.
    private val sceneLock = Any()

    // AudioRecord/столец WAV/джоб чтения/поколение переприсваиваются входом
    // сцены под sceneLock и обнуляются в cleanup-участках (проверка «только
    // если это же»), видимость между потоками — @Volatile. Столец (issue #68)
    // вместо ByteArrayOutputStream: PCM стримится в файл cacheDir, память
    // записи не растёт с длительностью.
    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var wavSink: WavSink? = null
    @Volatile
    private var readJob: Job? = null

    /**
     * Флаг поколения текущей записи (ревью раунд 3): цикл чтения захватывает
     * его локально и завершается при смене поколения — условие while больше
     * не зависит от глобального isRecording, который stop()+startRecording()
     * возвращают в true, оставляя старый цикл читать уже освобождённый
     * AudioRecord. Переприсваивается входом сцены старта под [sceneLock]
     * (ревью раунд 4), снимается сценой остановки — тоже под замком.
     */
    @Volatile
    private var generation: AtomicBoolean? = null

    /**
     * Начать запись с микрофона.
     * Тяжёлая инициализация AudioRecord — вне блокировки; перевод состояния
     * в «записываем» (проверка активной записи, установка полей и поколения,
     * старт цикла чтения) — единой сценой под [sceneLock] (ревью раунд 4),
     * чтобы конкурентный вход cancel()/stopRecording() не перемешался со
     * стартом.
     * @return true при успехе, false при ошибке (нет разрешения/устройства).
     */
    fun startRecording(): Boolean {
        if (isRecording) return true

        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Нет разрешения RECORD_AUDIO — запись невозможна")
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Timber.e("Не удалось определить буфер AudioRecord")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException при создании AudioRecord")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord не инициализирован")
            record.release()
            return false
        }

        // Потоковый WAV-столец в каталог кэша (issue #68, по образцу
        // временных файлов импорта моделей): каталог кэша система чистит
        // сама, живой файл всё равно удаляет вызывающий код после
        // распознавания (TranslateViewModel.transcribeAudio).
        val sink = try {
            WavSink(
                File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav"),
                SAMPLE_RATE,
                CHANNELS,
                BITS_PER_SAMPLE
            )
        } catch (e: Exception) {
            Timber.e(e, "Не удалось создать файл потоковой записи")
            record.release()
            return false
        }

        return synchronized(sceneLock) {
            if (isRecording) {
                // Пока инициализировали устройство, запись уже запустил
                // повторный вход — выбрасываем свеже созданные экземпляры.
                record.release()
                sink.discard()
                return@synchronized true
            }
            audioRecord = record
            // Поколение этой записи: цикл чтения ниже замыкает локальную ссылку
            // и выходит, когда stop()/cancel() снимают именно это поколение,
            // даже если к тому моменту уже стартовала новая запись.
            val currentGeneration = AtomicBoolean(true)
            generation = currentGeneration
            wavSink = sink
            _recordingElapsedMs.value = 0L
            // startRecording() может бросить IllegalStateException (ревью раунд 5):
            // освобождаем рекордер и откатываем сцену, иначе залипший экземпляр
            // микрофона никто не снимет (cancel() увидит isRecording==false).
            try {
                record.startRecording()
            } catch (e: IllegalStateException) {
                Timber.e(e, "AudioRecord.startRecording() не удался")
                record.release()
                audioRecord = null
                wavSink = null
                sink.discard()
                generation = null
                return@synchronized false
            }
            _recordingFlow.value = true

            readJob = scope.launch {
                val readBuffer = ByteArray(minBuffer)
                val startedAtNs = System.nanoTime()
                var lastReportedMs = 0L
                var writtenBytes = 0L
                // Условие — флаг поколения (ревью раунд 3), а не глобальный
                // isRecording: при stop()+startRecording() пре-generation цикл
                // обязан завершиться, а не читать освобождённый AudioRecord
                // новой записи (IllegalStateException / гонка освобождения).
                while (currentGeneration.get()) {
                    val read = record.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        synchronized(sink) {
                            sink.write(readBuffer, 0, read)
                        }
                        writtenBytes += read
                        // Потолок длительности (issue #68): защита и диска, и
                        // длительности STT; авто-остановку с сохранением делает
                        // ViewModel по MAX_RECORDING_MS — здесь лишь приём
                        // данных прекращается (stopRecording корректно завершит
                        // запись уже принятыми данными).
                        if (writtenBytes >= MAX_PCM_BYTES) {
                            Timber.w(
                                "Достигнут потолок записи ${MAX_RECORDING_MS / 1000} с — " +
                                    "приём данных остановлен"
                            )
                            break
                        }
                        val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                        if (elapsedMs - lastReportedMs >= 1000) {
                            lastReportedMs = elapsedMs
                            _recordingElapsedMs.value = elapsedMs
                        }
                    } else if (read < 0) {
                        // Ошибка чтения: continue превратился бы в busy-spin
                        // (read возвращает <0 немедленно). Завершаем цикл чтения;
                        // остановка и сохранение — как обычно через stopRecording().
                        Timber.e("AudioRecord.read() вернул ошибку $read — цикл чтения остановлен")
                        break
                    }
                }
            }
            true
        }
    }

    /** Цели освобождения, зафиксированные сценой остановки под [sceneLock]. */
    private class StoppedScene(
        val job: Job?,
        val record: AudioRecord?,
        val sink: WavSink?
    )

    /**
     * Фиксированная сцена остановки (ревью раунд 4): null — активной записи
     * нет; иначе под [sceneLock] — перевод [isRecording] в false, снятие
     * поколения этой записи и захват целевых объектов. Долгий cleanup
     * (join цикла чтения, stop/release, WAV) вызывающий выполняет вне замка.
     */
    private fun captureStopScene(): StoppedScene? {
        if (!isRecording) return null
        return synchronized(sceneLock) {
            if (!isRecording) return@synchronized null
            _recordingFlow.value = false
            val job = readJob
            readJob = null
            // Целевой AudioRecord захватывается в момент вызова (ревью раунд 2):
            // если в окне join() стартует новая запись, освобождается только
            // снятый рекордер, а поле обнуляется лишь пока в нём всё ещё он же.
            val captured = audioRecord
            val capturedSink = wavSink
            // Снятие поколения этой записи (ревью раунд 3): цикл чтения выходит
            // сам, независимо от возможного startRecording() в окне join().
            val capturedGeneration = generation
            capturedGeneration?.set(false)
            if (generation === capturedGeneration) {
                generation = null
            }
            StoppedScene(job, captured, capturedSink)
        }
    }

    /**
     * Общий преамбул cleanup-участка остановки (одинаков в [cancel] и
     * [stopRecording]): дождаться выхода цикла чтения, чтобы не освободить
     * AudioRecord посреди блокирующего read(), и отсоединить захваченный
     * рекордер от поля — только пока в нём всё ещё он же (новая запись,
     * стартованная в окне join(), этим вызовом не освобождается).
     */
    private suspend fun detachStoppedScene(scene: StoppedScene) {
        scene.job?.join()
        synchronized(sceneLock) {
            if (audioRecord === scene.record) {
                audioRecord = null
            }
        }
    }

    /**
     * Принудительно остановить запись без сохранения WAV (выгрузка рантаймов
     * при ON_STOP, ревью): рекордер — app-scope синглтон и без остановки
     * микрофон оставался захваченным после сворачивания приложения.
     * Несохранённые данные отбрасываются (потоковый файл удаляется,
     * issue #68); активное [stopRecording] (isRecording уже false)
     * не трогается.
     */
    fun cancel() {
        val scene = captureStopScene() ?: return
        scope.launch {
            // Дожидаемся выхода цикла чтения, чтобы не освободить AudioRecord
            // посреди блокирующего read() (тот же порядок, что в stopRecording).
            detachStoppedScene(scene)
            withContext(Dispatchers.IO) {
                try {
                    scene.record?.stop()
                } catch (e: IllegalStateException) {
                    // Буфер мог быть пуст — не критично.
                }
                scene.record?.release()
                scene.sink?.discard()
                _recordingElapsedMs.value = 0L
                Timber.d("Запись с микрофона снята при выгрузке рантаймов")
            }
        }
    }

    /**
     * Остановить запись и завершить потоковый WAV-файл (проставить размеры
     * в заголовке, issue #68). Накопленного в памяти PCM нет — пик памяти
     * записи не зависит от её длительности.
     * @return путь к WAV-файлу (каталог кэша) или null при ошибке/пустой записи.
     */
    suspend fun stopRecording(): String? {
        val scene = captureStopScene() ?: return null
        // Тот же сценарий вызова, что в cancel(): новая запись, стартованная
        // в окне join(), этим вызовом не освобождается и не сохраняется.
        detachStoppedScene(scene)

        // Блокирующие вызовы (AudioRecord.stop/release, закрытие и патч WAV) — на IO.
        return withContext(Dispatchers.IO) {
            try {
                scene.record?.stop()
            } catch (e: IllegalStateException) {
                // Буфер мог быть пуст — не критично.
            }
            scene.record?.release()
            _recordingElapsedMs.value = 0L

            val sink = scene.sink
            if (sink == null || sink.pcmSize == 0L) {
                Timber.w("Пустая запись: PCM-данных нет")
                sink?.discard()
                return@withContext null
            }

            // Цикл чтения уже дожидан (detachStoppedScene) — поток никто не
            // пишет; закрытие с патчем RIFF/data размеров на месте.
            if (sink.closeAndPatch()) {
                sink.file.absolutePath
            } else {
                if (!sink.file.delete()) {
                    Timber.w("Не удалось удалить битый WAV после сбоя патча: ${sink.file.path}")
                }
                null
            }
        }
    }
}
