package com.plox.llmtranslate.util

import timber.log.Timber
import java.io.File

/**
 * Чтение PCM из WAV-файла (16-bit, mono).
 * Используется в sherpa-onnx STT.
 */
object WavReader {

    /**
     * Прочитать PCM из WAV-файла как FloatArray (16-bit → float /32768).
     * @return массив сэмплов в диапазоне [-1.0, 1.0] или пустой массив при ошибке.
     */
    fun readPcmAsFloat(file: File): FloatArray {
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
            samples[idx] = ((hi shl 8) or lo) / 32768.0f
            idx++
            pos += 2
        }
        return samples
    }

    /**
     * Найти чанк "data" в RIFF-заголовке.
     * @return пара (начало PCM, размер PCM) или null, если WAV некорректен.
     */
    private fun locateDataChunk(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 44 ||
            bytes[0].toInt() != 'R'.code ||
            bytes[1].toInt() != 'I'.code ||
            bytes[2].toInt() != 'F'.code ||
            bytes[3].toInt() != 'F'.code
        ) {
            Timber.w("Некорректный WAV-заголовок")
            return null
        }

        var pos = 12
        while (pos + 8 <= bytes.size) {
            if (bytes[pos].toInt() == 'd'.code &&
                bytes[pos + 1].toInt() == 'a'.code &&
                bytes[pos + 2].toInt() == 't'.code &&
                bytes[pos + 3].toInt() == 'a'.code
            ) {
                return (pos + 8) to readIntLE(bytes, pos + 4)
            }
            // Переходим к следующему чанку (с выравниванием по 2 байта).
            val chunkSize = readIntLE(bytes, pos + 4)
            pos += 8 + chunkSize + (chunkSize and 1)
        }
        return null
    }

    /** Прочитать int32 little-endian. */
    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
