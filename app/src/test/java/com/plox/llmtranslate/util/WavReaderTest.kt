package com.plox.llmtranslate.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Тесты парсера WAV (RIFF): поиск data-чанка и декодирование 16-bit PCM.
 * Чистая логика — без Robolectric/Android.
 */
class WavReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun id4(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    /** Собрать RIFF-чанк: id + u32 размер + тело (+ pad при нечётном размере). */
    private fun chunkFourcc(id: String, body: ByteArray): ByteArray {
        val pad = if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        return id4(id) + le32(body.size) + body + pad
    }

    /** WAV: RIFF/WAVE + fmt + произвольные чанки перед data + data(PCM). */
    private fun wav(pcm: ByteArray, extraChunks: ByteArray = ByteArray(0)): ByteArray {
        val fmt = chunkFourcc("fmt ", ByteArray(16))
        val body = id4("WAVE") + fmt + extraChunks + chunkFourcc("data", pcm)
        return id4("RIFF") + le32(body.size) + body
    }

    /** fmt-чанк 16 байт + data с явным declaredDataSize (для проверки клампинга). */
    private fun wavWithDeclaredDataSize(pcm: ByteArray, declaredDataSize: Int): ByteArray {
        val fmt = chunkFourcc("fmt ", ByteArray(16))
        val data = id4("data") + le32(declaredDataSize) + pcm
        val body = id4("WAVE") + fmt + data
        return id4("RIFF") + le32(body.size) + body
    }

    private val pcm = byteArrayOf(0x00, 0x40, 0x00.toByte(), 0xC0.toByte()) // +16384, -16384

    private fun write(bytes: ByteArray): File {
        val f = tmp.newFile("test.wav")
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `file over size limit is rejected without loading`() {
        // Ревью #79: файл больше MAX_WAV_BYTES — явный отказ (пустой массив)
        // до чтения в память. Разрежённый файл — без факической затраты дискового объёма.
        val f = tmp.newFile("huge.wav")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(WavReader.MAX_WAV_BYTES + 1) }
        assertTrue(WavReader.readPcmAsFloat(f).isEmpty())
        // Ровно на границе лимита — читается как обычно (битый WAV → пуст).
        java.io.RandomAccessFile(f, "rw").use { it.setLength(WavReader.MAX_WAV_BYTES) }
        assertTrue(WavReader.readPcmAsFloat(f).isEmpty())
    }

    @Test
    fun `standard wav locates data chunk after fmt`() {
        val bytes = wav(pcm)
        val loc = requireNotNull(WavReader.locateDataChunk(bytes))
        val (start, size) = loc
        assertEquals(pcm.size, size)
        // PCM начинается после RIFF(12) + fmt(8+16) + заголовка data(8) = смещение 44.
        assertEquals(44, start)
        assertEquals(start + pcm.size, bytes.size)
    }

    @Test
    fun `decodes 16-bit little-endian samples to normalized floats`() {
        val samples = WavReader.readPcmAsFloat(write(wav(pcm)))
        assertEquals(2, samples.size)
        assertEquals(0.5f, samples[0], 1e-6f)
        assertEquals(-0.5f, samples[1], 1e-6f)
    }

    @Test
    fun `data chunk not first - after LIST chunk`() {
        val list = chunkFourcc("LIST", id4("INFO") + id4("INAM") + le32(4) + byteArrayOf(0x41, 0x00, 0x00, 0x00))
        val bytes = wav(pcm, extraChunks = list)
        val loc = requireNotNull(WavReader.locateDataChunk(bytes))
        val (start, size) = loc
        assertEquals(pcm.size, size)
        // Тело LIST = 4+4+4+4 = 16 (чётное, без pad) -> data на 36+8+16+8 = 68.
        assertEquals(68, start)
        val samples = WavReader.readPcmAsFloat(write(bytes))
        assertEquals(2, samples.size)
        assertEquals(0.5f, samples[0], 1e-6f)
    }

    @Test
    fun `odd sized chunks are skipped with padding`() {
        // Один нечётный чанк (проверка выравнивания в обходе).
        val odd = chunkFourcc("note", byteArrayOf(1, 2, 3))
        val bytes = wav(pcm, extraChunks = odd)
        assertNotNull(WavReader.locateDataChunk(bytes))
    }

    @Test
    fun `declared data larger than file is clamped`() {
        val samples = WavReader.readPcmAsFloat(write(wavWithDeclaredDataSize(pcm, 1000)))
        // Физически в файле только pcm.size байт -> 2 сэмпла.
        assertEquals(2, samples.size)
        assertEquals(-0.5f, samples[1], 1e-6f)
    }

    @Test
    fun `garbage before RIFF is rejected`() {
        // Мусор перед WAV: RIFF должен быть строго в начале файла —
        // иначе null и пустой PCM, без падения и без чтения мусора как PCM.
        val bytes = id4("ID3") + ByteArray(10) + wav(pcm)
        assertNull(WavReader.locateDataChunk(bytes))
        assertTrue(WavReader.readPcmAsFloat(write(bytes)).isEmpty())
    }

    @Test
    fun `missing data chunk returns null`() {
        val fmt = chunkFourcc("fmt ", ByteArray(16))
        val body = id4("WAVE") + fmt + chunkFourcc("note", byteArrayOf(1, 2, 3, 4))
        val bytes = id4("RIFF") + le32(body.size) + body
        assertNull(WavReader.locateDataChunk(bytes))
    }

    @Test
    fun `too short or bad magic returns null`() {
        assertNull(WavReader.locateDataChunk(ByteArray(10)))
        val notWav = wav(pcm)
        notWav[1] = 'X'.code.toByte()
        assertNull(WavReader.locateDataChunk(notWav))
    }

    @Test
    fun `negative chunk size terminates scan instead of looping`() {
        // Битый WAV: отрицательный размер чанка сдвигает позицию назад
        // (здесь ровно на 0 смещения) — без защиты обход зацикливается навсегда.
        val junk = id4("junk") + le32(-8) + ByteArray(24)
        val fmt = chunkFourcc("fmt ", ByteArray(16))
        val body = id4("WAVE") + junk + fmt + chunkFourcc("data", pcm)
        val bytes = id4("RIFF") + le32(body.size) + body
        assertNull(WavReader.locateDataChunk(bytes))
        assertTrue(WavReader.readPcmAsFloat(write(bytes)).isEmpty())
    }

    @Test
    fun `huge chunk size terminates scan instead of overflowing position`() {
        // Ревью: заявленный размер чанка, превышающий размер файла, без
        // ограничения переполняет Int-позицию обхода (выход за массив/
        // зацикливание) — должен быть явный отказ null.
        val huge = id4("junk") + le32(Int.MAX_VALUE) + ByteArray(24)
        val fmt = chunkFourcc("fmt ", ByteArray(16))
        val body = id4("WAVE") + huge + fmt + chunkFourcc("data", pcm)
        val bytes = id4("RIFF") + le32(body.size) + body
        assertNull(WavReader.locateDataChunk(bytes))
        assertTrue(WavReader.readPcmAsFloat(write(bytes)).isEmpty())
    }

    @Test
    fun `mismatched fmt is tolerated but data still located`() {
        // Формат вне ожидаемого (16-bit mono 16 кГц) — только предупреждение
        // в лог, данные читаются как раньше (совместимость с чужими WAV).
        val fmtBody = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(3) // format=IEEE float
            .putShort(2) // channels=2
            .putInt(44100)
            .putInt(352800)
            .putShort(8)
            .putShort(16)
        val fmt = id4("fmt ") + le32(fmtBody.array().size) + fmtBody.array()
        val data = id4("data") + le32(pcm.size) + pcm
        val body = id4("WAVE") + fmt + data
        val bytes = id4("RIFF") + le32(body.size) + body
        val loc = requireNotNull(WavReader.locateDataChunk(bytes))
        assertEquals(pcm.size, loc.second)
        assertEquals(body.size + 8 - pcm.size, loc.first)
    }
}
