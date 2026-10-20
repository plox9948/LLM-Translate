package com.plox.llmtranslate.data.catalog

import android.content.Context
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.ModelCard
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #83, п.10: дедупликация каталога по id при слиянии assets и
 * удалённого каталога (первые вхождения побеждают) — дубли удалённого
 * каталога раньше давали дубли карточек в UI.
 */
class RemoteCatalogMergeTest {

    private val parser = ModelCatalogParser()

    private val provider = RemoteCatalogProvider(
        mockk<Context>(relaxed = true),
        mockk<AssetsModelCatalogProvider>(relaxed = true),
        parser,
        mockk<ModelPreferences>(relaxed = true)
    )

    /** Карточки через реальный парсер (поля-заглушки, displayName — маркер). */
    private fun cards(vararg defs: Pair<String, String>): List<ModelCard> = parser.parse(
        """{"version":1,"models":[${defs.joinToString(",") { """{"id":"${it.first}","displayName":"${it.second}","category":"TRANSLATION","runtime":"MARIAN_ONNX","sizeBytes":1}""" }}]}"""
    )

    @Test
    fun `duplicate remote ids merge into single first card`() {
        // Дубли обходят дедуп парсера: каждый cards() — отдельный валидный каталог.
        val remote = cards("a" to "A1") + cards("a" to "A2") + cards("b" to "B")
        val merged = provider.mergeCatalogs(emptyList(), remote)

        assertEquals(listOf("a", "b"), merged.map(ModelCard::id))
        // Первое вхождение побеждает.
        assertEquals("A1", merged.first { it.id == "a" }.displayName)
    }

    @Test
    fun `remote overrides assets by id and duplicate asset ids are deduped`() {
        // Дубли assets обходят дедуп парсера (списки конкатенируются) —
        // merge обязан оставить единственную позицию с замещением из remote.
        val assets = cards("a" to "Asset1") + cards("a" to "Asset2")
        val remote = cards("a" to "Remote")

        val merged = provider.mergeCatalogs(assets, remote)
        assertEquals(1, merged.size)
        assertEquals("Remote", merged.single().displayName)
    }

    @Test
    fun `merge keeps unique assets and adds only new remote ids`() {
        val assets = cards("x" to "X", "a" to "AssetA")
        val remote = cards("a" to "RemoteA", "y" to "Y")

        val merged = provider.mergeCatalogs(assets, remote)
        assertEquals(listOf("x", "a", "y"), merged.map(ModelCard::id))
        assertEquals("RemoteA", merged.first { it.id == "a" }.displayName)
    }
}
