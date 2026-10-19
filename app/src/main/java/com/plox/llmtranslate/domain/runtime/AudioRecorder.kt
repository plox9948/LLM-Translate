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
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
            Timber.e(e, "Не удалось записать WAV: ${file.path}")
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
     * @return true при успехе; false, если файл исчез (кэш вычищен системой
     *   во время записи, issue #69) или патч не удался.
     */
    fun patchSizes(file: File, pcmBytes: Long): Boolean {
        // Issue #69: RandomAccessFile("rw") при исчезнувшем файле создал бы
        // фантомный 44-байтный WAV — «успешная» пустая запись. Проверяем
        // наличие файла до патча; отсутствующий не создаём (запись потеряна,
        // вызывающий получит false и null-контракт stopRecording).
        if (!file.exists()) {
            Timber.w("Файл записи исчез во время записи (кэш вычищен): ${file.path}")
            return false
        }
        return try {
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
        try {
            stream.write(WavWriter.buildHeader(0, sampleRate, channels, bitsPerSample))
        } catch (e: Exception) {
            // Без закрытия FileOutputStream оставался незакрытым дескриптором
            // при отказе записи заголовка (fd-утечка, ревью); конструктор
            // бросает исходное исключение вызывающему.
            try {
                stream.close()
            } catch (closeError: Exception) {
                Timber.w(closeError, "Не удалось закрыть поток после сбоя заголовка: ${file.path}")
            }
            throw e
        }
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
         * Жёсткий потолок длительности записи, мс (issues #68, #83): максимум
         * осмысленного STT-сценария (3 минуты). При достижении цикл чтения
         * САМ финализирует сцену: закрывает WAV с патчем размеров, освобождает
         * AudioRecord и эмиттирует recordingFlow=false — при мёртвом ViewModel
         * (переход на другой таб снимает recordingLimitJob) микрофон иначе
         * оставался занятым навсегда. Авто-остановка ViewModel сохранена: её
         * stopRecording() получает сохранённый путь записи (см.
         * [limitFinishedWavPath]).
         */
        const val MAX_RECORDING_MS = 180_000L

        /** PCM-байт, соответствующих потолку [MAX_RECORDING_MS]. */
        val MAX_PCM_BYTES: Long =
            MAX_RECORDING_MS / 1000L * (SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8).toLong()

        /** Задержка ветки read==0, чтобы приём не превращался в busy-spin. */
        private const val READ_IDLE_DELAY_MS = 10L

        /**
         * Возраст «осиротевших» recording_*.wav в кэше, удаляемых при старте
         * записи и при старте приложения (ревью): файлы предыдущих
         * поколений/убитых процессов иначе не чистятся никогда. Моложе порога
         * не трогаем — это может быть живой файл конкурентной записи.
         */
        private const val ORPHAN_WAV_TTL_MS = 24 * 60 * 60 * 1000L

        /**
         * Общая точка очистки osиротевших временных WAV записей (cacheDir,
         * префикс recording_): вызывается при старте записи (ниже) и при
         * старте приложения (OfflineTranslatorApp) — логика в одном месте.
         */
        internal fun cleanupOrphanRecordings(cacheDir: File) {
            val cutoff = System.currentTimeMillis() - ORPHAN_WAV_TTL_MS
            val stale = cacheDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("recording_") &&
                    it.name.endsWith(".wav") && it.lastModified() < cutoff
            } ?: return
            for (f in stale) {
                if (!f.delete()) {
                    Timber.w("Не удалось удалить osиротевшую запись: ${f.path}")
                }
            }
            if (stale.isNotEmpty()) {
                Timber.d("Удалено osиротевших временных WAV: ${stale.size}")
            }
        }
    }

    /**
     * Удалить из кэша osиротевшие временные WAV записей старше
     * [ORPHAN_WAV_TTL_MS] (общий хелпер [cleanupOrphanRecordings], ревью).
     */
    private fun cleanupOrphanRecordings() = cleanupOrphanRecordings(context.cacheDir)

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
     * Путь WAV, авто-дозакрытого по потолку [MAX_RECORDING_MS] (issue #83):
     * цикл чтения уже снял сцену, поэтому обычный [stopRecording] вернул бы
     * null — первый вызов [stopRecording] отдаёт этот путь, распознавание
     * ViewModel выполняется как обычно. Снимается стартом новой сцены и в
     * [cancel] (файл при этом удаляется).
     */
    @Volatile
    private var limitFinishedWavPath: String? = null

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

        // Osиротевшие WAV прошлых записей из кэша не удаляет никто
        // (вызывающий снимает только свой живой файл) — чистим старые
        // при старте (ревью).
        cleanupOrphanRecordings()

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
            // Новая запись инвалидирует WAV, дозакрытый по потолку старой
            // сцены (issue #83): его больше никто не отдаст, удаляет orphan-
            // очистка кэша.
            limitFinishedWavPath = null
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
                    // Ревью #83: на части OEM при смерти аудио-HAL read() не
                    // возвращает код ошибки, а бросает IllegalStateException —
                    // без try/catch исключение роняло весь readJob: индикатор
                    // залипал (_recordingFlow оставался true), AudioRecord —
                    // занят. Путь тот же, что для read<0 (finishAfterReadFailure;
                    // снимает сцену только пока поколение наше).
                    val read = try {
                        record.read(readBuffer, 0, readBuffer.size)
                    } catch (e: IllegalStateException) {
                        Timber.e(e, "AudioRecord.read() бросил исключение — запись аварийно останавливается")
                        if (currentGeneration.get()) {
                            finishAfterReadFailure(currentGeneration)
                        }
                        -1
                    } catch (e: IllegalArgumentException) {
                        Timber.e(e, "AudioRecord.read() с некорректными аргументами — запись аварийно останавливается")
                        if (currentGeneration.get()) {
                            finishAfterReadFailure(currentGeneration)
                        }
                        -1
                    }
                    if (read > 0) {
                        try {
                            synchronized(sink) {
                                sink.write(readBuffer, 0, read)
                            }
                        } catch (e: IOException) {
                            // Сбой записи WAV (заполненный диск/сбой файловой
                            // системы): необработанный IOException ронял readJob
                            // при живых generation/_recordingFlow — индикатор
                            // залипал, а микрофон оставался занят до потолка
                            // 3 мин. Та же аварийная сцена, что и для read()<0
                            // (ревью контрольного раунда 4).
                            Timber.e(e, "Ошибка записи WAV — запись аварийно останавливается")
                            if (currentGeneration.get()) {
                                finishAfterReadFailure(currentGeneration)
                            }
                            break
                        }
                        writtenBytes += read
                        // Потолок длительности (issues #68, #83): защита и диска, и
                        // длительности STT. Цикл сам закрывает сцену с сохранением
                        // данных (WAV патчится, AudioRecord освобождается,
                        // recordingFlow=false); авто-остановка ViewModel по
                        // MAX_RECORDING_MS остаётся запасной — её stopRecording()
                        // получит дозакрытый путь из limitFinishedWavPath.
                        if (writtenBytes >= MAX_PCM_BYTES) {
                            Timber.w(
                                "Достигнут потолок записи ${MAX_RECORDING_MS / 1000} с — " +
                                    "запись закрывается автоматически"
                            )
                            if (currentGeneration.get()) {
                                finishAtRecordingLimit(currentGeneration)
                            }
                            break
                        }
                        val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                        if (elapsedMs - lastReportedMs >= 1000) {
                            lastReportedMs = elapsedMs
                            _recordingElapsedMs.value = elapsedMs
                        }
                    } else if (read < 0) {
                        // Ошибка чтения (перехват микрофона звонком на Android 9,
                        // сбой AudioRecord): continue превратился бы в busy-spin
                        // (read возвращает <0 немедленно). Сцена остановки
                        // запускается прямо здесь: без неё индикатор залипал бы
                        // (_recordingFlow остался true), а AudioRecord —
                        // неосвобождён до самого потолка 3 мин (ревью повт. аудита).
                        Timber.e(
                            "AudioRecord.read() вернул ошибку $read — запись аварийно останавливается"
                        )
                        // Поколение уже снято — сцену нашей записи перехватил
                        // stopRecording()/cancel(), он и освобождает; если в
                        // этом окне стартовала новая запись, наша обработка её
                        // убивать не должна (ревью раунда 3).
                        if (currentGeneration.get()) {
                            finishAfterReadFailure(currentGeneration)
                        }
                        break
                    } else {
                        // read == 0: данных в буфере нет, чтение вернулось бы
                        // немедленно — без задержки это busy-spin по CPU (ревью).
                        delay(READ_IDLE_DELAY_MS)
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
     * [expectedGeneration] (ревью раунда 3): если задан — сцена снимается
     * только когда активное поколение всё ещё это же; иначе (нашу сцену уже
     * снял конкурентный вход и, возможно, стартовала новая запись) — null
     * без побочных эффектов, чтобы не убить чужую новую сцену.
     */
    private fun captureStopScene(expectedGeneration: AtomicBoolean? = null): StoppedScene? {
        if (!isRecording) return null
        return synchronized(sceneLock) {
            if (!isRecording) return@synchronized null
            if (expectedGeneration != null && generation !== expectedGeneration) {
                return@synchronized null
            }
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
     * Аварийная остановка из самого цикла чтения при ошибке read()<0
     * или сбое записи WAV (ревью повт. аудита, ревью контрольного раунда 4):
     * индикатор не должен залипаться, а микрофон —
     * оставаться занятым. Вызывается ИЗ корутины цикла чтения, поэтому
     * [detachStoppedScene].join собственного джоба исключён (самодjoin
     * deadlock'нулся бы): read() уже вернулся — рекордер не занят, его
     * можно освобождать сразу. Освобождение под NonCancellable+IO —
     * отмена внешней корутины не должна обрывать освобождение на середине.
     * Данные частично записанного с дефектного устройства аудио ненадёжны —
     * WAV отбрасывается (вызывающий stopRecording() получит null).
     * [currentGeneration] (ревью раунда 3): сцена снимается и освобождается
     * только если она принадлежит поколению этого же цикла чтения — иначе
     * нашу сцену уже снял stopRecording()/cancel() (или стартовала новая
     * запись), и освобождать должен захвативший, а не мы.
     */
    private suspend fun finishAfterReadFailure(currentGeneration: AtomicBoolean) {
        val scene = captureStopScene(expectedGeneration = currentGeneration) ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            // joinscene.job пропускаем: это текущая корутина.
            synchronized(sceneLock) {
                if (audioRecord === scene.record) {
                    audioRecord = null
                }
            }
            try {
                scene.record?.stop()
            } catch (e: IllegalStateException) {
                // Рекордер мог быть уже освобождён системой — не критично.
            }
            scene.record?.release()
            scene.sink?.discard()
            _recordingElapsedMs.value = 0L
            Timber.w("Запись аварийно остановлена: AudioRecord освобождён, WAV отброшен")
        }
    }

    /**
     * Остановка по достижению потолка [MAX_RECORDING_MS] из самого цикла
     * чтения (issue #83). В отличие от [finishAfterReadFailure] данные
     * исправны: WAV не отбрасывается, а корректно закрывается с патчем
     * размеров, путь сохраняется в [limitFinishedWavPath] — штатная
     * авто-остановка ViewModel дойдёт до [stopRecording] и получит этот путь.
     * Если ViewModel мёртв — микрофон и индикатор уже освобождены этим
     * путём, а WAV подчистит orphan-очистка кэша. Вызывается ИЗ корутины
     * цикла чтения, собственный join сцены исключён (самодjoin), как и в
     * [finishAfterReadFailure].
     */
    private suspend fun finishAtRecordingLimit(currentGeneration: AtomicBoolean) {
        val scene = captureStopScene(expectedGeneration = currentGeneration) ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(sceneLock) {
                if (audioRecord === scene.record) {
                    audioRecord = null
                }
            }
            try {
                scene.record?.stop()
            } catch (e: IllegalStateException) {
                // Рекордер мог быть уже освобождён системой — не критично.
            }
            scene.record?.release()
            _recordingElapsedMs.value = 0L
            val sink = scene.sink
            if (sink != null && sink.pcmSize > 0L && sink.closeAndPatch()) {
                limitFinishedWavPath = sink.file.absolutePath
                Timber.w("Запись закрыта по потолку длительности: WAV сохранён, AudioRecord освобождён")
            } else {
                sink?.discard()
                Timber.w("Запись по потолку длительности без PCM-данных (или сбой патча WAV)")
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
        val scene = captureStopScene()
        // WAV, дозакрытый по потолку (issue #83), при выгрузке тоже не нужен —
        // удаляем вместе со сценой.
        val staleWav = limitFinishedWavPath
        limitFinishedWavPath = null
        if (scene == null && staleWav == null) return
        scope.launch {
            // Сцена освобождения целиком под NonCancellable+IO (ревью повт.
            // аудита): join цикла, stop/release и отброс WAV не должны
            // прерываться отменой — иначе гибель корутины в этом окне
            // оставляла бы AudioRecord unreleased (микрофон занят до GC).
            // Дожидаемся выхода цикла чтения, чтобы не освободить AudioRecord
            // посреди блокирующего read() (тот же порядок, что в stopRecording).
            withContext(NonCancellable + Dispatchers.IO) {
                if (staleWav != null && !File(staleWav).delete()) {
                    Timber.w("Не удалось удалить WAV потолковой записи: $staleWav")
                }
                if (scene == null) return@withContext
                detachStoppedScene(scene)
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
        // Самозакрытие по потолку (issue #83): сцена уже снята циклом чтения —
        // путь WAV был дозакрыт и сохранён; отдаём его первому вызову
        // (обычный captureStopScene на снятой сцене вернул бы null и
        // распознавание сорвалось бы при живой ViewModel).
        val autoPath = limitFinishedWavPath
        if (autoPath != null) {
            limitFinishedWavPath = null
            return if (File(autoPath).exists()) autoPath else null
        }
        val scene = captureStopScene() ?: return null
        // Сцена освобождения целиком под NonCancellable+IO (ревью повт.
        // аудита): ViewModel, дождавшийся отмены, иначе уничтожался в окне
        // join/stop/release и AudioRecord оставался unreleased (микрофон
        // занят до GC финализатором). Новая запись, стартованная в окне
        // join(), этим вызовом не освобождается и не сохраняется.
        return withContext(NonCancellable + Dispatchers.IO) {
            detachStoppedScene(scene)

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
