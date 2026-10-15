package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Юнит-тесты разрешения файлов пачки Bergamot в [SlimtRuntime]
 * (без нативного слоя — только выбор файлов на диске).
 */
class SlimtRuntimeTest {

    private val runtime = SlimtRuntime()

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
}
