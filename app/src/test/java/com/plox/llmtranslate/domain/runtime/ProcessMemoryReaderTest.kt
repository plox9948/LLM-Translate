package com.plox.llmtranslate.domain.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit-тесты чтения PSS процесса (ревью #83): контракт KDoc — нулевой (и
 * отрицательный) totalPss от AMS не является валидным значением и не должен
 * отдаваться как «процесс ест 0 МБ»; валидное значение конвертируется КБ→МБ.
 */
class ProcessMemoryReaderTest {

    private fun readerWith(pssKb: Int?, context: Context): ProcessMemoryReader {
        val am = mockk<ActivityManager>()
        val info: Debug.MemoryInfo? = if (pssKb == null) {
            null
        } else {
            mockk<Debug.MemoryInfo>().also { every { it.totalPss } returns pssKb }
        }
        every { am.getProcessMemoryInfo(any()) } returns if (pssKb == null) emptyArray() else arrayOf(info!!)
        every {
            context.getSystemService(Context.ACTIVITY_SERVICE)
        } returns am
        return ProcessMemoryReader(context)
    }

    @Test
    fun `valid totalPss is converted to megabytes`() {
        mockkStatic(Process::class)
        try {
            every { Process.myPid() } returns 4242
            val context = mockk<Context>()
            val reader = readerWith(pssKb = 2560, context = context)
            assertEquals(2, runBlocking { reader.processPssMb() })
        } finally {
            unmockkStatic(Process::class)
        }
    }

    @Test
    fun `zero totalPss from AMS treated as failure not as zero megabytes`() {
        mockkStatic(Process::class)
        try {
            every { Process.myPid() } returns 4242
            val context = mockk<Context>()
            val reader = readerWith(pssKb = 0, context = context)
            assertNull(runBlocking { reader.processPssMb() })
        } finally {
            unmockkStatic(Process::class)
        }
    }

    @Test
    fun `empty AMS result yields null`() {
        mockkStatic(Process::class)
        try {
            every { Process.myPid() } returns 4242
            val context = mockk<Context>()
            val reader = readerWith(pssKb = null, context = context)
            assertNull(runBlocking { reader.processPssMb() })
        } finally {
            unmockkStatic(Process::class)
        }
    }
}
