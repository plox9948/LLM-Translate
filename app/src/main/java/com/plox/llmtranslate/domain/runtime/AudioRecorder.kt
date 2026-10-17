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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
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
    }

    /**
     * Флаг активной записи: пишет рекордер (start/cancel/stop), читают цикл
     * чтения (другой поток) и UI. Бэкинг — [recordingFlow] (re-view раунд 2):
     * StateFlow даёт и междупоточную видимость (happens-before), и реактивную
     * подписку ViewModel, чтобы индикатор не залипал при внешнем cancel().
     */
    private val _recordingFlow = MutableStateFlow(false)

    /** Реактивное состояние записи для UI (true — идёт запись с микрофона). */
    val recordingFlow: StateFlow<Boolean> = _recordingFlow

    /** Активна ли запись сейчас. */
    val isRecording: Boolean
        get() = _recordingFlow.value

    // Поле освобождается/подменяется только потоком, переводившим
    // _recordingFlow; видимость — через volatile-механизм StateFlow.
    private var audioRecord: AudioRecord? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var readJob: Job? = null

    /**
     * Флаг поколения текущей записи (ревью раунд 3): цикл чтения захватывает
     * его локально и завершается при смене поколения — условие while больше
     * не зависит от глобального isRecording, который stop()+startRecording()
     * возвращают в true, оставляя старый цикл читать уже освобождённый
     * AudioRecord. Переприсваивается, как и [audioRecord], только потоком,
     * переводившим _recordingFlow.
     */
    private var generation: AtomicBoolean? = null

    /**
     * Начать запись с микрофона.
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

        audioRecord = record
        // Поколение этой записи: цикл чтения ниже замыкает локальную ссылку
        // и выходит, когда stop()/cancel() снимают именно это поколение,
        // даже если к тому моменту уже стартовала новая запись.
        val currentGeneration = AtomicBoolean(true)
        generation = currentGeneration
        val buffer = ByteArrayOutputStream()
        pcmBuffer = buffer
        record.startRecording()
        _recordingFlow.value = true

        readJob = scope.launch {
            val readBuffer = ByteArray(minBuffer)
            // Условие — флаг поколения (ревью раунд 3), а не глобальный
            // isRecording: при stop()+startRecording() пре-generation цикл
            // обязан завершиться, а не читать освобождённый AudioRecord
            // новой записи (IllegalStateException / гонка освобождения).
            while (currentGeneration.get()) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    synchronized(buffer) {
                        buffer.write(readBuffer, 0, read)
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
        return true
    }

    /**
     * Принудительно остановить запись без сохранения WAV (выгрузка рантаймов
     * при ON_STOP, ревью): рекордер — app-scope синглтон и без остановки
     * микрофон оставался захваченным после сворачивания приложения.
     * Несозранённые PCM-данные отбрасываются; активное [stopRecording]
     * (isRecording уже false) не трогается.
     */
    fun cancel() {
        if (!isRecording) return
        _recordingFlow.value = false
        val job = readJob
        readJob = null
        // Целевой AudioRecord захватывается в момент вызова (ревью раунд 2):
        // если в окне join() стартует новая запись, освобождается только
        // снятый рекордер, а поле обнуляется лишь пока в нём всё ещё он же.
        val captured = audioRecord
        val capturedBuffer = pcmBuffer
        // Снятие поколения этой записи (ревью раунд 3): цикл чтения выходит
        // сам, независимо от возможного startRecording() в окне join().
        val capturedGeneration = generation
        capturedGeneration?.set(false)
        if (generation === capturedGeneration) {
            generation = null
        }
        scope.launch {
            // Дожидаемся выхода цикла чтения, чтобы не освободить AudioRecord
            // посреди блокирующего read() (тот же порядок, что в stopRecording).
            job?.join()
            if (audioRecord === captured) {
                audioRecord = null
            }
            try {
                captured?.stop()
            } catch (e: IllegalStateException) {
                // Буфер мог быть пуст — не критично.
            }
            captured?.release()
            synchronized(capturedBuffer) { capturedBuffer.reset() }
            Timber.d("Запись с микрофона снята при выгрузке рантаймов")
        }
    }

    /**
     * Остановить запись и сохранить WAV-файл.
     * @return путь к WAV-файлу или null при ошибке.
     */
    suspend fun stopRecording(): String? {
        if (!isRecording) return null
        _recordingFlow.value = false
        val job = readJob
        readJob = null
        // Тот же захват целевых объектов в момент вызова, что в cancel()
        // (ревью раунд 2): новая запись, стартованная в окне join(), этим
        // вызовом не освобождается и не сохраняется.
        val captured = audioRecord
        val capturedBuffer = pcmBuffer
        // Поколение снимается до join() (ревью раунд 3): цикл чтения
        // завершается по своему флагу, а не по глобальному isRecording.
        val capturedGeneration = generation
        capturedGeneration?.set(false)
        if (generation === capturedGeneration) {
            generation = null
        }
        job?.join()
        if (audioRecord === captured) {
            audioRecord = null
        }

        // Блокирующие вызовы (AudioRecord.stop/release, запись WAV) — на IO.
        return withContext(Dispatchers.IO) {
            try {
                captured?.stop()
            } catch (e: IllegalStateException) {
                // Буфер мог быть пуст — не критично.
            }
            captured?.release()

            val pcm = synchronized(capturedBuffer) { capturedBuffer.toByteArray() }
            if (pcm.isEmpty()) {
                Timber.w("Пустая запись: PCM-данных нет")
                return@withContext null
            }

            val audioDir = context.getExternalFilesDir("audio")
                ?: context.filesDir
            val file = File(audioDir, "recording_${System.currentTimeMillis()}.wav")
            if (WavWriter.writeWav(file, pcm, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE)) {
                file.absolutePath
            } else {
                null
            }
        }
    }
}
