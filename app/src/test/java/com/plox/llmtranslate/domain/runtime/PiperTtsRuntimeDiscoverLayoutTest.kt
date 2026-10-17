package com.plox.llmtranslate.domain.runtime

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Тесты раскладки Piper-каталога (ревью раунд 3): приоритет канонического
 * имени model.onnx (без учёта регистра) над более крупными попутными
 * *.onnx; иначе — крупнейший файл; tokens.txt обязателен.
 */
class PiperTtsRuntimeDiscoverLayoutTest {

    /** Контекст не используется до load(), раскладка — чистая файловая логика. */
    private val runtime = PiperTtsRuntime(
        mockk(relaxed = true),
        CoroutineScope(Dispatchers.Unconfined)
    )

    /** Временный каталог с tokens.txt и заданными *.onnx-файлами указанного содержимого. */
    private fun tempDir(vararg onnxFiles: Pair<String, String>): File {
        val dir = createTempDirectory("piper_layout").toFile()
        File(dir, "tokens.txt").writeText("token list")
        onnxFiles.forEach { (name, content) -> File(dir, name).writeText(content) }
        return dir
    }

    @Test
    fun `model onnx preferred over larger co-located onnx`() {
        val dir = tempDir(
            "voice-backup.onnx" to "X".repeat(1000),
            "model.onnx" to "small"
        )
        try {
            val layout = runtime.discoverLayout(dir)
            assertEquals(File(dir, "model.onnx").absolutePath, layout?.modelPath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `model onnx matched case insensitively`() {
        val dir = tempDir(
            "voice-backup.onnx" to "X".repeat(1000),
            "Model.ONNX" to "small"
        )
        try {
            val layout = runtime.discoverLayout(dir)
            assertEquals(File(dir, "Model.ONNX").absolutePath, layout?.modelPath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `largest onnx used when no model onnx`() {
        val dir = tempDir(
            "voice-small.onnx" to "small",
            "voice-large.onnx" to "X".repeat(500)
        )
        try {
            val layout = runtime.discoverLayout(dir)
            assertEquals(File(dir, "voice-large.onnx").absolutePath, layout?.modelPath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing tokens txt yields no layout`() {
        val dir = createTempDirectory("piper_layout_no_tokens").toFile()
        File(dir, "model.onnx").writeText("small")
        try {
            assertNull(runtime.discoverLayout(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
