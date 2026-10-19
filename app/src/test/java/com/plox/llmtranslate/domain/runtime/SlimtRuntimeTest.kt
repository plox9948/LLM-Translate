package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.ModelCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Юнит-тесты разрешения файлов пачки Bergamot в [SlimtRuntime]
 * (без нативного слоя — только выбор файлов на диске) и освобождения
 * пучка удалённой модели из LRU-кэша (issue #40).
 */
class SlimtRuntimeTest {

    private val runtime = SlimtRuntime(FakeModelRetentionProvider(keep = true))

    private fun touch(dir: File, name: String): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(byteArrayOf(1)) }
    }

    @Test
    fun `resolves shared vocab pack`() {
        val dir = File.createTempFile("pack", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        try {
            touch(dir, "model.ruen.intgemm.alphas.bin")
            touch(dir, "lex.50.50.ruen.s2t.bin")
            val vocab = touch(dir, "vocab.ruen.spm")
            touch(dir, "unexpected-readme.txt")
            val files = runtime.resolvePackageFiles(dir)
            assertNotNull(files)
            assertNull(files!!.targetVocab)
            org.junit.Assert.assertEquals(vocab, files.vocab)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolves two vocab pack with src and trg`() {
        val dir = File.createTempFile("pack2", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        try {
            touch(dir, "model.enzh.intgemm.alphas.bin")
            touch(dir, "lex.50.50.enzh.s2t.bin")
            val src = touch(dir, "srcvocab.enzh.spm")
            val trg = touch(dir, "trgvocab.enzh.spm")
            val files = runtime.resolvePackageFiles(dir)
            assertNotNull(files)
            org.junit.Assert.assertEquals(src, files!!.vocab)
            org.junit.Assert.assertEquals(trg, files!!.targetVocab)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `incomplete pack returns null`() {
        val dir = File.createTempFile("pack3", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        try {
            touch(dir, "model.ruen.intgemm.alphas.bin")
            // нет shortlist
            assertNull(runtime.resolvePackageFiles(dir))
            touch(dir, "lex.50.50.ruen.s2t.bin")
            // нет vocab
            assertNull(runtime.resolvePackageFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing directory returns null`() {
        assertNull(runtime.resolvePackageFiles(File("does-not-exists-xyz")))
    }

    /**
     * Сверка языковой пары из имён файлов (ревью): файлы из разных пачек,
     * подмешанные в один каталог, — null (LoadFailed наверху), эквивалентные
     * двусторонние метки (ruen/enru) — корректная пачка.
     */
    @Test
    fun `mixed language pair files are rejected`() {
        val dir = File.createTempFile("packmix", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        try {
            touch(dir, "model.ruen.intgemm.alphas.bin")
            touch(dir, "lex.50.50.ruen.s2t.bin")
            touch(dir, "vocab.enzh.spm")
            assertNull(runtime.resolvePackageFiles(dir))
            // Исправляем vocab на пару модели (enru ≡ ruen) — пачка валидна.
            File(dir, "vocab.enzh.spm").delete()
            touch(dir, "vocab.enru.spm")
            assertNotNull(runtime.resolvePackageFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pack language pair extraction`() {
        assertEquals(setOf("ru", "en"), runtime.packLanguagePair("model.ruen.intgemm.alphas.bin"))
        assertEquals(setOf("en", "ru"), runtime.packLanguagePair("lex.50.50.enru.s2t.bin"))
        assertEquals(setOf("ru", "en"), runtime.packLanguagePair("vocab.ruen.spm"))
        // Неразложимые имена — null (сверка невозможна, не блокирует загрузку).
        assertNull(runtime.packLanguagePair("model.bin"))
        assertNull(runtime.packLanguagePair("readme.txt"))
    }

    /**
     * Эвик активного пучка при удалении модели (issue #40): пучок изымается
     * из кэша, активный id сбрасывается, [loadedModels] больше не содержит id.
     * Пучки с handle=0L не трогают JNI-слой (нативная библиотека в unit-тесте
     * не загружена) — проверка механизма кэша без нативного инференса.
     */
    @Test
    fun `releaseModel evicts active bundle and resets loaded state`() = runTest {
        val scaffoldField = SlimtRuntime::class.java.getDeclaredField("engineCache")
        scaffoldField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val scaffold = scaffoldField.get(runtime) as EngineCacheScaffold<Any>
        val cache = scaffold.cache

        val bundleClass = Class.forName(
            "com.plox.llmtranslate.domain.runtime.SlimtRuntime\$ModelBundle"
        )
        val constructor = bundleClass.getDeclaredConstructor(
            Long::class.javaPrimitiveType, LoadedModelInfo::class.java
        )
        constructor.isAccessible = true
        fun bundle(id: String): Any = constructor.newInstance(
            0L, LoadedModelInfo(id, "Pack $id", ModelCategory.TRANSLATION, 10)
        )

        cache.put("ru-en", bundle("ru-en"))
        cache.put("en-ru", bundle("en-ru"))
        scaffold.activeId = "ru-en"

        runtime.releaseModel("ru-en")

        assertEquals(listOf("en-ru"), cache.all().map { it.first })
        assertNull("активный id удалённой модели сброшен", scaffold.activeId)
        assertEquals(
            listOf("en-ru"),
            runtime.loadedModels.value.map { it.modelId }
        )

        // Повторный эвик отсутствующего id — no-op, чужой пучок цел.
        runtime.releaseModel("ru-en")
        assertEquals(listOf("en-ru"), cache.all().map { it.first })
        assertEquals(
            listOf("en-ru"),
            runtime.loadedModels.value.map { it.modelId }
        )
    }
}
