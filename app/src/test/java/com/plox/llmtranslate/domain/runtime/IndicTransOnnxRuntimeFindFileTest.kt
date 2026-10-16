package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Тесты поиска файлов модели: точное совпадение .onnx должно
 * бить *.onnx.data (external data весов).
 */
class IndicTransOnnxRuntimeFindFileTest {

    private val runtime = IndicTransOnnxRuntime(FakeModelRetentionProvider(keep = true))

    @Test
    fun `exact onnx preferred over onnx-data, substring fallback for json`() {
        val dir = createTempDirectory("indictrans_find_file").toFile().apply {
            File(this, "encoder_model.onnx").createNewFile()
            File(this, "encoder_model.onnx.data").createNewFile()
            File(this, "decoder_model.onnx").createNewFile()
            File(this, "decoder_shared.onnx.data").createNewFile()
            File(this, "decoder_with_past_model.onnx").createNewFile()
            File(this, "tokenizer_src.json").createNewFile()
        }
        try {
            // Точное совпадение: .onnx.data не перехватывается.
            assertEquals("encoder_model.onnx", runtime.findFile(dir, "encoder_model")?.name)
            assertEquals("decoder_model.onnx", runtime.findFile(dir, "decoder_model")?.name)
            assertEquals(
                "decoder_with_past_model.onnx",
                runtime.findFile(dir, "decoder_with_past_model")?.name
            )
            // Fallback по подстроке для файлов без .onnx.
            assertEquals("tokenizer_src.json", runtime.findFile(dir, "tokenizer_src")?.name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
