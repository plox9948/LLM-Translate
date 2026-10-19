package com.plox.llmtranslate.util

import timber.log.Timber
import java.io.File

/**
 * Чтение PCM из WAV-файла (16-bit, mono).
 * Используется в sherpa-onnx STT.
 */
object WavReader {

    /**
     * Потолок размера WAV: файл читается в память целиком; consumers —
     * короткие STT-записи (потолок рекордера ~3 мин, единицы МБ). Файл
     * заведомо большего размера — битый/чужой вход: отказ с явной ошибкой
     * в логе вместо риска OOM (ревью #79).
     */
    const val MAX_WAV_BYTES: Long = 50L * 1024 * 1024

    /**
     * Прочитать PCM из WAV-файла как FloatArray (16-bit → float /32768).
     * @return массив сэмплов в диапазоне [-1.0, 1.0] или пустой массив при
     *         ошибке (битый WAV, файл больше [MAX_WAV_BYTES]).
     */
    fun readPcmAsFloat(file: File): FloatArray {
        val fileBytes = file.length()
        if (fileBytes > MAX_WAV_BYTES) {
            Timber.e("WAV слишком большой: $fileBytes байт (макс. $MAX_WAV_BYTES): ${file.name}")
            return FloatArray(0)
        }
        val bytes = file.readBytes()
        val (start, size) = locateDataChunk(bytes) ?: return FloatArray(0)
        val end = minOf(start + size, bytes.size)
        if (end <= start) return FloatArray(0)

        val count = (end - start) / 2
        val samples = FloatArray(count)
        var idx = 0
        var pos = start
        while (pos + 1 < end) {
            // 16-bit little-endian signed → нормализованный float.
            val lo = bytes[pos].toInt() and 0xFF
            val hi = bytes[pos + 1].toInt() and 0xFF
            val raw = (hi shl 8) or lo
            // Знаковое расширение 16-битного значения (0x8000 = -32768).
            val signed = if (raw >= 0x8000) raw - 0x10000 else raw
            samples[idx] = signed / 32768.0f
            idx++
            pos += 2
        }
        return samples
    }

    /**
     * Найти чанк "data" в RIFF-заголовке.
     * @return пара (начало PCM, размер PCM) или null, если WAV некорректен.
     */
    // internal — покрыто unit-тестом (чистая логика, без Android).
    internal fun locateDataChunk(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 44 ||
            bytes[0].toInt() != 'R'.code ||
            bytes[1].toInt() != 'I'.code ||
            bytes[2].toInt() != 'F'.code ||
            bytes[3].toInt() != 'F'.code
        ) {
            Timber.w("Некорректный WAV-заголовок")
            return null
        }

        var fmtSeen = false
        var fmtMatches = false
        var fmtBodyPos = -1
        var pos = 12
        while (pos + 8 <= bytes.size) {
            if (bytes[pos].toInt() == 'd'.code &&
                bytes[pos + 1].toInt() == 'a'.code &&
                bytes[pos + 2].toInt() == 't'.code &&
                bytes[pos + 3].toInt() == 'a'.code
            ) {
                if (!fmtSeen) {
                    Timber.w("WAV: fmt-чанк отсутствует (ожидаем 16-bit mono %d Гц)", EXPECTED_SAMPLE_RATE)
                } else if (!fmtMatches) {
                    Timber.w(
                        "WAV: fmt-чанк не соответствует ожидаемому формату " +
                            "(16-bit mono %d Гц PCM). Фактически: ${fmtHint(bytes, fmtBodyPos)}",
                        EXPECTED_SAMPLE_RATE
                    )
                }
                return (pos + 8) to readIntLE(bytes, pos + 4)
            }
            if (bytes[pos].toInt() == 'f'.code &&
                bytes[pos + 1].toInt() == 'm'.code &&
                bytes[pos + 2].toInt() == 't'.code &&
                bytes[pos + 3].toInt() == ' '.code
            ) {
                fmtSeen = true
                fmtBodyPos = pos + 8
                fmtMatches = fmtMatchesExpected(bytes, fmtBodyPos)
            }
            // Переходим к следующему чанку (с выравниванием по 2 байта).
            val chunkSize = readIntLE(bytes, pos + 4)
            // Битый заголовок: отрицательный размер сдвигает pos назад или
            // не двигает — обход зацикливается (висячий вызов под мьютексом STT).
            if (chunkSize < 0) {
                Timber.w("WAV: отрицательный размер чанка в позиции $pos")
                return null
            }
            // Заявленный чанк за концом массива — битый заголовок; без
            // ограничения Int-позиция `pos += 8 + chunkSize + pad` может
            // переполниться (ревью).
            if (chunkSize > bytes.size - pos) {
                Timber.w("WAV: размер чанка $chunkSize выходит за пределы файла (позиция $pos)")
                return null
            }
            pos += 8 + chunkSize + (chunkSize and 1)
        }
        return null
    }

    /**
     * Проверка fmt-чанка (body — первым байт после заголовка чанка): ожидаем
     * PCM (format=1), mono, [EXPECTED_SAMPLE_RATE] Гц, 16 бит — тот же формат,
     * что пишет рекордер (AudioRecorder.SAMPLE_RATE/CHANNELS/BITS_PER_SAMPLE).
     * Только предупреждение в лог: consumers (sherpa-onnx STT) читают PCM как
     * есть, вердикт о совместимости — не здесь.
     */
    private fun fmtMatchesExpected(bytes: ByteArray, body: Int): Boolean {
        if (body + 16 > bytes.size) return false
        val format = readUInt16LE(bytes, body)
        val channels = readUInt16LE(bytes, body + 2)
        val sampleRate = readIntLE(bytes, body + 4)
        val bitsPerSample = readUInt16LE(bytes, body + 14)
        return format == EXPECTED_PCM_FORMAT &&
            channels == EXPECTED_CHANNELS &&
            sampleRate == EXPECTED_SAMPLE_RATE &&
            bitsPerSample == EXPECTED_BITS_PER_SAMPLE
    }

    /** Фактические поля fmt для диагностического лога. */
    private fun fmtHint(bytes: ByteArray, body: Int): String {
        if (body + 16 > bytes.size) return "fmt усечён"
        return "format=${readUInt16LE(bytes, body)}, channels=${readUInt16LE(bytes, body + 2)}, " +
            "rate=${readIntLE(bytes, body + 4)}, bits=${readUInt16LE(bytes, body + 14)}"
    }

    /** Прочитать int32 little-endian. */
    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** Прочитать uint16 little-endian. */
    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private const val EXPECTED_PCM_FORMAT = 1
    private const val EXPECTED_CHANNELS = 1
    private const val EXPECTED_SAMPLE_RATE = 16000
    private const val EXPECTED_BITS_PER_SAMPLE = 16
}
