package com.plox.llmtranslate.data.catalog

import android.content.Context
import com.plox.llmtranslate.data.prefs.ModelPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Ревью инварианта «кэш ≥ применённая версия»: применённая версия удалённого
 * каталога фиксируется только при успешной записи кэша. Иначе при следующем
 * старте без сети отдаются только assets (remotePartApplied=false), и
 * осиротевшие READY-модели применённой версии не чистятся никогда.
 */
class RemoteCatalogCommitTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val preferences = mockk<ModelPreferences>()

    private val catalogJson = """{"version":5,"models":[]}"""

    private fun providerFor(dir: File) = RemoteCatalogProvider(
        mockk<Context> { every { filesDir } returns dir },
        mockk<AssetsModelCatalogProvider>(relaxed = true),
        ModelCatalogParser(),
        preferences
    )

    @Test
    fun `commit pins version only after successful cache write`() = runTest {
        coEvery { preferences.setLastRemoteCatalogVersion(any()) } returns Unit
        val dir = tmpFolder.newFolder()
        val provider = providerFor(dir)

        assertTrue(provider.commitRemoteCatalog(catalogJson, 5))

        assertEqualsJsonCached(File(dir, "remote_catalog.json"))
        coVerify(exactly = 1) { preferences.setLastRemoteCatalogVersion(5) }
    }

    @Test
    fun `failed cache write does not pin applied version`() = runTest {
        coEvery { preferences.setLastRemoteCatalogVersion(any()) } returns Unit
        // filesDir под обычным файлом: запись кэша невозможна (нет каталога).
        val brokenParent = tmpFolder.newFile("not-a-directory")
        val provider = providerFor(File(brokenParent, "sub"))

        assertFalse(provider.commitRemoteCatalog(catalogJson, 5))

        coVerify(exactly = 0) { preferences.setLastRemoteCatalogVersion(any()) }
    }

    private fun assertEqualsJsonCached(file: File) {
        assertTrue("файл кэша не создан", file.isFile)
        assertTrue(
            "кэш не совпадает с записанным JSON",
            file.readText(Charsets.UTF_8) == catalogJson
        )
    }
}
