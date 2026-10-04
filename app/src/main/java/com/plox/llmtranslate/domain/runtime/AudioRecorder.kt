package com.plox.llmtranslate.domain.runtime

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
    }

    var isRecording: Boolean = false
        private set

    private var audioRecord: AudioRecord? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var readJob: Job? = null

    /**
     * Начать запись с микрофона.
     * @return true при успехе, false при ошибке (нет разрешения/устройства).
     */
    fun startRecording(): Boolean {
        if (isRecording) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Timber.e("Не удалось определить буфер AudioRecord")
            return false
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord не инициализирован")
            record.release()
            return false
        }

        audioRecord = record
        pcmBuffer = ByteArrayOutputStream()
        record.startRecording()
        isRecording = true

        readJob = scope.launch {
            val buffer = ByteArray(minBuffer)
            while (isRecording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        pcmBuffer.write(buffer, 0, read)
                    }
                }
            }
        }
        return true
    }

    /**
     * Остановить запись и сохранить WAV-файл.
     * @return путь к WAV-файлу или null при ошибке.
     */
    fun stopRecording(): String? {
        if (!isRecording) return null
        isRecording = false
        readJob?.join()
        readJob = null

        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            // Буфер мог быть пуст — не критично.
        }
        record?.release()

        val pcm = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
        if (pcm.isEmpty()) {
            Timber.w("Пустая запись: PCM-данных нет")
            return null
        }

        val audioDir = context.getExternalFilesDir("audio")
            ?: context.filesDir
        val file = File(audioDir, "recording_${System.currentTimeMillis()}.wav")
        return if (WavWriter.writeWav(file, pcm, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE)) {
            file.absolutePath
        } else {
            null
        }
    }

    /**
     * Остановить запись без сохранения (отмена).
     */
    fun cancelRecording() {
        isRecording = false
        readJob?.join()
        readJob = null
        audioRecord?.release()
        audioRecord = null
        pcmBuffer = ByteArrayOutputStream()
    }
}
