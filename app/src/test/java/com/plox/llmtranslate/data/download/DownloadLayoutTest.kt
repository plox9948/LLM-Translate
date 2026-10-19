package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit-тесты раскладки загрузок [DownloadLayout] (issue #66): для purely-archive
 * моделей (SHERPA/PIPER, все URL — архивы) очистка осиротевших остатков обязана
 * сносить каталог модели целиком — ArchiveInstaller создаёт его до распаковки,
 * и при отмене в нём остаются каталоги (в отличие от файлов, их
 * discardPartial не удаляет).
 */
class DownloadLayoutTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun touch(path: File) {
        path.parentFile?.mkdirs()
        path.writeText("x")
    }

    @Test
    fun `archive-only sherpa model cleanup removes model dir and root parts`() {
        val runtimeDir = folder.newFolder("SHERPA")
        val modelId = "sherpa-ru"
        val url = "https://example/model.tar.bz2"

        // Остатки прерванной загрузки: части докачки и скачанный архив в
        // корне рантайма + полу-распакованное содержимое в каталоге модели
        // (файлы и каталоги).
        touch(File(runtimeDir, "model.tar.bz2.part"))
        touch(File(runtimeDir, "model.tar.bz2.part.meta"))
        touch(File(runtimeDir, "model.tar.bz2"))
        val modelDir = DownloadLayout.modelDir(runtimeDir, modelId)
        touch(File(modelDir, "model.onnx"))
        File(modelDir, "sub").mkdirs()
        touch(File(modelDir, "sub/tokens.txt"))

        DownloadLayout.deleteOrphanedFiles(runtimeDir, RuntimeType.SHERPA, modelId, listOf(url))

        assertFalse(".part не удалён", File(runtimeDir, "model.tar.bz2.part").exists())
        assertFalse(".part.meta не удалён", File(runtimeDir, "model.tar.bz2.part.meta").exists())
        assertFalse("осиротевший архив не удалён", File(runtimeDir, "model.tar.bz2").exists())
        assertFalse("каталог осиротевшей модели не снесён", modelDir.exists())
    }

    @Test
    fun `piper archive layout cleanup also removes model dir`() {
        val runtimeDir = folder.newFolder("PIPER_ONNX")
        val modelId = "piper-en"

        val modelDir = DownloadLayout.modelDir(runtimeDir, modelId)
        modelDir.mkdirs() // создан install() до распаковки

        DownloadLayout.deleteOrphanedFiles(
            runtimeDir,
            RuntimeType.PIPER_ONNX,
            modelId,
            listOf("https://example/voice.tar.bz2"),
        )
        assertFalse("пустой каталог отменённой модели не удалён", modelDir.exists())
    }

    @Test
    fun `single-file onnx model cleanup touches only its own files`() {
        val runtimeDir = folder.newFolder("INDICTRANS_ONNX")
        val modelId = "indictrans"

        touch(File(runtimeDir, "decoder_model.onnx.part"))
        touch(File(runtimeDir, "decoder_model.onnx"))
        // Чужая READY-модель в каталоге той же группы рантайма.
        val otherDir = DownloadLayout.modelDir(runtimeDir, "other-model")
        touch(File(otherDir, "model.onnx"))

        DownloadLayout.deleteOrphanedFiles(
            runtimeDir,
            RuntimeType.INDICTRANS_ONNX,
            modelId,
            listOf("https://example/decoder_model.onnx"),
        )

        assertFalse(".part осиротевшей докачки не удалён", File(runtimeDir, "decoder_model.onnx.part").exists())
        assertFalse("осиротевший целевой файл не удалён", File(runtimeDir, "decoder_model.onnx").exists())
        assertTrue("каталог другой модели задет очисткой", otherDir.isDirectory)
    }

    @Test
    fun `single-file cleanup removes new-scheme prefixed and legacy parts`() {
        // Ревью, п.15: имена целей едины через targetFileName — очистка обязана
        // находить новую схему (префикс modelId), промежуточный санитизированный
        // basename без query («decoder.onnx.part» от legacy-цели «?raw=true»)
        // и не задевать имена чужих моделей.
        val runtimeDir = folder.newFolder("INDICTRANS_ONNX")
        val modelId = "indictrans"
        val url = "https://example/decoder.onnx?raw=true"

        touch(File(runtimeDir, "indictrans__decoder.onnx"))
        touch(File(runtimeDir, "indictrans__decoder.onnx.part"))
        touch(File(runtimeDir, "indictrans__decoder.onnx.part.meta"))
        touch(File(runtimeDir, "decoder.onnx.part"))
        // Чужая модель с тем же basename — не задевается.
        touch(File(runtimeDir, "other__decoder.onnx"))

        DownloadLayout.deleteOrphanedFiles(runtimeDir, RuntimeType.INDICTRANS_ONNX, modelId, listOf(url))

        assertFalse("целевое имя новой схемы не удалено", File(runtimeDir, "indictrans__decoder.onnx").exists())
        assertFalse(".part новой схемы не удалён", File(runtimeDir, "indictrans__decoder.onnx.part").exists())
        assertFalse(".part.meta новой схемы не удалён", File(runtimeDir, "indictrans__decoder.onnx.part.meta").exists())
        assertFalse("legacy .part без query не удалён", File(runtimeDir, "decoder.onnx.part").exists())
        assertTrue("имя чужой модели задето очисткой", File(runtimeDir, "other__decoder.onnx").isFile)
    }

    @Test
    fun `blank basename cleanup covers hash fallback and legacy single-file names`() {
        // Пустой basename URL: новая схема — «file_0_<sha>» с префиксом id,
        // legacy-остатки — «file_0» (Archive/Slimt) и «<modelId>.onnx» (Onnx).
        val runtimeDir = folder.newFolder("MARIAN_ONNX")
        val modelId = "legacy-id"
        val url = "http://host/path/"

        val fallback = DownloadLayout.targetFileName(modelId, url, 0, inModelDir = false)
        touch(File(runtimeDir, fallback))
        touch(File(runtimeDir, "$fallback.part"))
        touch(File(runtimeDir, "file_0.part"))
        touch(File(runtimeDir, "legacy-id.onnx.part"))
        touch(File(runtimeDir, "legacy-id.onnx"))

        DownloadLayout.deleteOrphanedFiles(runtimeDir, RuntimeType.MARIAN_ONNX, modelId, listOf(url))

        assertFalse("fallback-цель новой схемы не удалена", File(runtimeDir, fallback).exists())
        assertFalse(".part новой схемы не удалён", File(runtimeDir, "$fallback.part").exists())
        assertFalse("legacy fallback «file_0» не удалён", File(runtimeDir, "file_0.part").exists())
        assertFalse("legacy «modelId.onnx» не удалён", File(runtimeDir, "legacy-id.onnx").exists())
        assertFalse("legacy .part «modelId.onnx» не удалён", File(runtimeDir, "legacy-id.onnx.part").exists())
    }

    @Test
    fun `prefixed names use separator outside sanitized alphabet and keep legacy candidates`() {
        // Issue #83, п.7: «~» не порождается ни sanitizeId, ни sanitizedBasename —
        // пары (id, basename), сталкивавшиеся при «__», теперь дают разные имена,
        // а legacy-имена прежней схемы остаются кандидатами для уже
        // установленных файлов.
        assertEquals(
            "a~_b.bin",
            DownloadLayout.targetFileName("a", "https://h/_b.bin", 0, inModelDir = false)
        )
        // Прежняя схема для этих же пар давала коллизию «a___b.bin»:
        // id "a" + basename "_b.bin" и id "a_" + basename "b.bin".
        val first = DownloadLayout.targetFileName("a", "https://h/_b.bin", 0, inModelDir = false)
        val second = DownloadLayout.targetFileName("a_", "https://h/b.bin", 0, inModelDir = false)
        assertTrue(first != second)

        val candidates = DownloadLayout.installedFileCandidates("a", "https://h/_b.bin", 0)
        assertTrue("новая схема не в кандидатах", candidates.contains("a~_b.bin"))
        assertTrue("legacy-склейка «__» не в кандидатах", candidates.contains("a___b.bin"))
        assertTrue("basename без префикса не в кандидатах", candidates.contains("_b.bin"))
    }

    @Test
    fun `root cleanup removes own leftovers of both schemes and spares neighbor model`() {
        // Issue #83, п.7: очистка удаляет остатки обеих схем своей модели и не
        // трогает новую схему соседней модели с похожим id.
        val runtimeDir = folder.newFolder("MARIAN_ONNX")
        val ownUrl = "https://h/b.bin"
        touch(File(runtimeDir, "a_~b.bin"))
        touch(File(runtimeDir, "a_~b.bin.part"))
        touch(File(runtimeDir, "a___b.bin"))
        touch(File(runtimeDir, "a___b.bin.part"))
        // Файл соседней READY-модели id "a" ("a~_b.bin") при прежней схеме
        // совпадал бы с legacy-кандидатом — теперь не должен удаляться...
        // NB: legacy-кандидат модели "a_" — «a___b.bin», он принадлежит обеих
        // схем; «a~_b.bin» — чужое имя новой схемы.
        touch(File(runtimeDir, "a~_b.bin"))

        DownloadLayout.deleteOrphanedFiles(runtimeDir, RuntimeType.MARIAN_ONNX, "a_", listOf(ownUrl))

        assertFalse("цель новой схемы не удалена", File(runtimeDir, "a_~b.bin").exists())
        assertFalse(".part новой схемы не удалён", File(runtimeDir, "a_~b.bin.part").exists())
        assertFalse("legacy-цель не удалена", File(runtimeDir, "a___b.bin").exists())
        assertFalse("legacy .part не удалён", File(runtimeDir, "a___b.bin.part").exists())
        assertTrue("файл новой схемы соседней модели задет очисткой", File(runtimeDir, "a~_b.bin").isFile)
    }

    @Test
    fun `deletePendingParts covers legacy prefixed parts too`() {
        // Issue #83: части докачки прежней схемы «id__base.part» тоже чистятся.
        val runtimeDir = folder.newFolder("MARIAN_ONNX")
        touch(File(runtimeDir, "mdl__part.onnx.part"))
        DownloadLayout.deletePendingParts(
            runtimeDir, RuntimeType.MARIAN_ONNX, "mdl", listOf("https://h/part.onnx")
        )
        assertFalse(File(runtimeDir, "mdl__part.onnx.part").exists())
    }
}
