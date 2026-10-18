package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Тесты формирования WAV-заголовка.
 */
class WavWriterTest {

    @Test
    fun `builds valid RIFF header`() {
        val pcmSize = 1000
        val header = WavWriter.buildHeader(pcmSize, 16000, 1, 16)

        assertEquals(44, header.size)
        // "RIFF"
        assertEquals('R'.code.toByte(), header[0])
        assertEquals('I'.code.toByte(), header[1])
        assertEquals('F'.code.toByte(), header[2])
        assertEquals('F'.code.toByte(), header[3])
        // RIFF size = 36 + pcmSize
        val riffSize = readIntLE(header, 4)
        assertEquals(36 + pcmSize, riffSize)
        // "WAVE"
        assertEquals('W'.code.toByte(), header[8])
        assertEquals('A'.code.toByte(), header[9])
        assertEquals('V'.code.toByte(), header[10])
        assertEquals('E'.code.toByte(), header[11])
        // "fmt "
        assertEquals('f'.code.toByte(), header[12])
        assertEquals('m'.code.toByte(), header[13])
        assertEquals('t'.code.toByte(), header[14])
        assertEquals(' '.code.toByte(), header[15])
        // fmt size = 16
        assertEquals(16, readIntLE(header, 16))
        // PCM format = 1
        assertEquals(1, readShortLE(header, 20))
        // channels = 1
        assertEquals(1, readShortLE(header, 22))
        // sample rate = 16000
        assertEquals(16000, readIntLE(header, 24))
        // byte rate = 16000 * 1 * 16 / 8 = 32000
        assertEquals(32000, readIntLE(header, 28))
        // block align = 1 * 16 / 8 = 2
        assertEquals(2, readShortLE(header, 32))
        // bits per sample = 16
        assertEquals(16, readShortLE(header, 34))
        // "data"
        assertEquals('d'.code.toByte(), header[36])
        assertEquals('a'.code.toByte(), header[37])
        assertEquals('t'.code.toByte(), header[38])
        assertEquals('a'.code.toByte(), header[39])
        // data size = pcmSize
        assertEquals(pcmSize, readIntLE(header, 40))
    }

    @Test
    fun `writes wav file with header and pcm`() {
        val pcm = ByteArray(200) { it.toByte() }
        val tempFile = Files.createTempFile("wav_test", ".wav").toFile()
        tempFile.deleteOnExit()

        val success = WavWriter.writeWav(tempFile, pcm, 16000, 1, 16)

        assertTrue(success)
        val bytes = tempFile.readBytes()
        // 44 (header) + 200 (pcm)
        assertEquals(44 + pcm.size, bytes.size)
        assertEquals('R'.code.toByte(), bytes[0])
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
