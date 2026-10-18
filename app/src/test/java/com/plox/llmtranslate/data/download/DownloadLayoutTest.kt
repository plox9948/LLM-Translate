package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
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
}
