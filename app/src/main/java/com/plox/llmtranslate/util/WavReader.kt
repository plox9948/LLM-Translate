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
     *         ошибке (битый WAV, файл больше [MAX_WAV_BYTES]); некорректный
     *         размер data-чанка (0, отрицательный, за EOF) — salvage по
     *         фактическому остатку файла (ревью #83).
     */
    fun readPcmAsFloat(file: File): FloatArray {
        val fileBytes = file.length()
        if (fileBytes > MAX_WAV_BYTES) {
            Timber.e("WAV слишком большой: $fileBytes байт (макс. $MAX_WAV_BYTES): ${file.name}")
            return FloatArray(0)
        }
        val bytes = file.readBytes()
        val (start, declaredSize) = locateDataChunk(bytes) ?: return FloatArray(0)
        if (start >= bytes.size) return FloatArray(0)
        var size = declaredSize
        if (size <= 0 || start.toLong() + size > bytes.size) {
            // Аномальный data-чанк: 0, 0xFFFFFFFF и просто размер за EOF
            // раньше молча давали пустое либо обрезанное чтение (ревью #83).
            // Фолбэк — PCM по фактический остаток файла: частично
            // распознаваемый звук лучше тишины, лог предупреждает о битости.
            // Остаток ограничивается концом RIFF-контейнера, когда заголовок
            // задаёт согласованную границу (замечание 8 ревью #83): иначе
            // не-PCM чанки и мусор, лежащие после объявленного контейнера,
            // декодировались бы как сэмплы. При несогласованном заголовке —
            // прежний фактический остаток файла (семантика salvage).
            Timber.w(
                "WAV: размер data-чанка $declaredSize в позиции $start не согласован " +
                    "с файлом (${bytes.size} байт) — читаем фактический остаток: ${file.name}"
            )
            size = salvageEnd(bytes, start) - start
        }
        val end = minOf(start.toLong() + size, bytes.size.toLong()).toInt()
        if (end <= start) return FloatArray(0)

        var count = (end - start) / 2
        if (count > MAX_SAMPLES) {
            Timber.w(
                "WAV: $count сэмплов больше потолка $MAX_SAMPLES — читаем потолок: ${file.name}"
            )
            count = MAX_SAMPLES
        }
        val samples = FloatArray(count)
        var idx = 0
        var pos = start
        while (idx < count && pos + 1 < end) {
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
     *
     * Ревью: предупреждение по fmt-чанку выдаётся только после полного обхода
     * (или нахождения fmt) — при расположении fmt после data прежнее
     * «fmt-чанк отсутствует» было ложным, а несоответствие формата не
     * проверялось вовсе. Контракт не меняется: (начало PCM, размер) или null
     * при битом заголовке.
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
        // data найден раньше fmt — запоминаем и продолжаем обход в поисках fmt.
        var pendingData: Pair<Int, Int>? = null

        var pos = 12
        while (pos + 8 <= bytes.size) {
            if (bytes[pos].toInt() == 'd'.code &&
                bytes[pos + 1].toInt() == 'a'.code &&
                bytes[pos + 2].toInt() == 't'.code &&
                bytes[pos + 3].toInt() == 'a'.code
            ) {
                if (fmtSeen) {
                    if (!fmtMatches) {
                        Timber.w(
                            "WAV: fmt-чанк не соответствует ожидаемому формату " +
                                "(16-bit mono %d Гц PCM). Фактически: ${fmtHint(bytes, fmtBodyPos)}",
                            EXPECTED_SAMPLE_RATE
                        )
                    }
                    return (pos + 8) to readIntLE(bytes, pos + 4)
                }
                if (pendingData == null) {
                    pendingData = (pos + 8) to readIntLE(bytes, pos + 4)
                }
            }
            if (bytes[pos].toInt() == 'f'.code &&
                bytes[pos + 1].toInt() == 'm'.code &&
                bytes[pos + 2].toInt() == 't'.code &&
                bytes[pos + 3].toInt() == ' '.code
            ) {
                fmtSeen = true
                fmtBodyPos = pos + 8
                fmtMatches = fmtMatchesExpected(bytes, fmtBodyPos)
                // fmt после data — предупреждение по факту сверки, ответ по data.
                pendingData?.let { (start, size) ->
                    if (!fmtMatches) {
                        Timber.w(
                            "WAV: fmt-чанк не соответствует ожидаемому формату " +
                                "(16-bit mono %d Гц PCM). Фактически: ${fmtHint(bytes, fmtBodyPos)}",
                            EXPECTED_SAMPLE_RATE
                        )
                    }
                    return start to size
                }
            }
            // Переходим к следующему чанку (с выравниванием по 2 байта).
            val chunkSize = readIntLE(bytes, pos + 4)
            // Битый заголовок: отрицательный размер сдвигает pos назад или
            // не двигает — обход зацикливается (висячий вызов под мьютексом STT).
            if (chunkSize < 0) {
                Timber.w("WAV: отрицательный размер чанка в позиции $pos")
                return finishWithPendingData(pendingData, fmtSeen, fmtMatches, bytes, fmtBodyPos)
            }
            // Заявленный чанк за концом массива — битый заголовок; без
            // ограничения Int-позиция `pos += 8 + chunkSize + pad` может
            // переполниться (ревью).
            if (chunkSize > bytes.size - pos) {
                Timber.w("WAV: размер чанка $chunkSize выходит за пределы файла (позиция $pos)")
                return finishWithPendingData(pendingData, fmtSeen, fmtMatches, bytes, fmtBodyPos)
            }
            pos += 8 + chunkSize + (chunkSize and 1)
        }
        // Полный обход завершён — честное предупреждение по fmt (он мог и не
        // встретиться, и лежать после data), ответ — по найденному data (ревью).
        return finishWithPendingData(pendingData, fmtSeen, fmtMatches, bytes, fmtBodyPos)
    }

    /**
     * Завершение обхода с ранее найденным data-чанком: предупреждение по
     * fmt выдаётся здесь (после полного обхода), ответ — пара из data
     * (контракт прежний); без pending data — null.
     */
    private fun finishWithPendingData(
        pendingData: Pair<Int, Int>?,
        fmtSeen: Boolean,
        fmtMatches: Boolean,
        bytes: ByteArray,
        fmtBodyPos: Int
    ): Pair<Int, Int>? {
        pendingData ?: return null
        if (!fmtSeen) {
            Timber.w("WAV: fmt-чанк отсутствует (ожидаем 16-bit mono %d Гц)", EXPECTED_SAMPLE_RATE)
        } else if (!fmtMatches) {
            Timber.w(
                "WAV: fmt-чанк не соответствует ожидаемому формату " +
                    "(16-bit mono %d Гц PCM). Фактически: ${fmtHint(bytes, fmtBodyPos)}",
                EXPECTED_SAMPLE_RATE
            )
        }
        return pendingData
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

    /**
     * Граница salvage-чтения битого data-чанка (замечание 8 ревью #83):
     * если RIFF-контейнер (размер в байтах заголовка 4..7, поле «размер
     * после RIFF») даёт границу строго внутри файла и после начала PCM —
     * salvage ограничен концом контейнера; иначе (заголовок битый/нулевой
     * или контейнер покрывает весь файл) — фактический конец файла.
     */
    // internal — покрыто unit-тестом (чистая логика, без Android).
    internal fun salvageEnd(bytes: ByteArray, start: Int): Int {
        if (bytes.size < 8) return bytes.size
        val containerEnd = 8L + (readIntLE(bytes, 4).toLong() and 0xFFFFFFFFL)
        return if (containerEnd in (start + 1L) until bytes.size.toLong()) {
            containerEnd.toInt()
        } else {
            bytes.size
        }
    }

    private const val EXPECTED_PCM_FORMAT = 1
    private const val EXPECTED_CHANNELS = 1
    private const val EXPECTED_SAMPLE_RATE = 16000
    private const val EXPECTED_BITS_PER_SAMPLE = 16

    /**
     * Длительность штатной STT-записи, с (зеркало AudioRecorder.MAX_RECORDING_MS;
     * самостоятельная константа — util не импортирует domain, замечание 7
     * ревью #83).
     */
    private const val MAX_RECORDING_SECONDS = 180

    /**
     * Потолок числа PCM-отсчётов (ревью #83): FloatArray аллоцировался по
     * заявленному data-чанку вплоть до [MAX_WAV_BYTES] — пик памяти ~150 МБ
     * (байты файла + 4 байта на сэмпл). Штатные consumers — записи рекордера:
     * потолок длительности × частота отсчёта = 180 с × 16 кГц; заявленный
     * чанок больше читается до потолка с предупреждением. Значение совпадает
     * с прежним AudioRecorder.MAX_PCM_BYTES/2, связь с рекордером — только
     * смысловая, обратный слой util→domain убран (замечание 7 ревью #83).
     */
    const val MAX_SAMPLES: Int = MAX_RECORDING_SECONDS * EXPECTED_SAMPLE_RATE
}
