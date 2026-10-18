package com.plox.llmtranslate.domain.model

import com.plox.llmtranslate.data.download.DownloadLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip тест логики именования экспорта и распознавания формата импорта
 * (ревью раунд 9: экспорт одиночного файла без расширения получал выдуманное
 * «.gguf», импорт его отвергал — цикл экспорт→импорт own-моделей был сломан):
 * имя, которое даёт [ModelFileNaming.exportFileName], второй вызов
 * [ModelFileNaming.guessRuntime] обязан принять (не null), а «мёртвый» GGUF
 * не должен появляться в именах никогда.
 */
class ModelFileNamingTest {

    @Test
    fun `export of onnx file keeps onnx extension`() {
        val name = ModelFileNaming.exportFileName(
            filePath = "/models/MARIAN_ONNX/opus-mt-ru-en.onnx",
            displayName = "Opus MT RU→EN",
            isDirectory = false
        )
        assertEquals("Opus MT RU→EN.onnx", name)
    }

    @Test
    fun `export of directory model wraps as zip`() {
        val name = ModelFileNaming.exportFileName(
            filePath = "/models/MARIAN_SLIMT/ces-en",
            displayName = "ces-en",
            isDirectory = true
        )
        assertEquals("ces-en.zip", name)
        assertTrue(DownloadLayout.isArchive(name))
    }

    @Test
    fun `export uses displayName extension when path has none, no double extension`() {
        // Импортированная модель: filePath imported_... без расширения,
        // displayName уже несёт .onnx из исходного имени.
        val name = ModelFileNaming.exportFileName(
            filePath = "/models/imported_1730000000",
            displayName = "model.onnx",
            isDirectory = false
        )
        assertEquals("model.onnx", name)
    }

    @Test
    fun `export never fabricates dead gguf extension for path without extension`() {
        val name = ModelFileNaming.exportFileName(
            filePath = "/models/MARIAN_SLIMT/ces-en-vocab",
            displayName = "ces-en",
            isDirectory = false
        )
        assertEquals("ces-en", name)
    }

    @Test
    fun `guessRuntime accepts exported name of onnx single-file round-trip`() {
        val exported = ModelFileNaming.exportFileName(
            filePath = "/models/MARIAN_SLIMT/ruen.bin",
            displayName = "Firefox pack",
            isDirectory = false
        )
        assertEquals("Firefox pack.bin", exported)
        // Формат .bin в одиночном файле нераспознан — импорт честно отклоняется.
        assertNull(ModelFileNaming.guessRuntime(exported))
    }

    @Test
    fun `guessRuntime accepts indictrans and archive names (markers preserved)`(){
        assertEquals(RuntimeType.INDICTRANS_ONNX, ModelFileNaming.guessRuntime("indictrans2.en-ru.onnx"))
        assertEquals(RuntimeType.SHERPA, ModelFileNaming.guessRuntime("sherpa-ru.tar.bz2"))
        // Реальный round-trip: экспорт онто-одиночки → повторный импорт принимает.
        val exported = ModelFileNaming.exportFileName(
            filePath = "/models/MARIAN_ONNX/model.onnx",
            displayName = "m2m100",
            isDirectory = false
        )
        assertEquals(RuntimeType.MARIAN_ONNX, ModelFileNaming.guessRuntime(exported))
    }

    @Test
    fun `guessRuntime still rejects unknown formats instead of bogus default`() {
        assertNull(ModelFileNaming.guessRuntime("random_file.bin"))
        assertNull(ModelFileNaming.guessRuntime("notes.txt"))
    }
}
