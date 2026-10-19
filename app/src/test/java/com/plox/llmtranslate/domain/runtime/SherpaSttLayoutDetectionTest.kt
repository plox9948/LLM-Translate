package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.RuntimeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Unit-тесты жёсткого детекта раскладок STT-каталога (ревью #83):
 * transducer/whisper распознаются только по каноническим sherpa-суффиксам,
 * NeMo/piper-подобные `model*.onnx` имеют приоритет над whisper-веткой,
 * при неоднозначности — явная ошибка с перечислением файлов (и на уровне
 * [SherpaSttEngine.detectModel], и сквозной проверкой в [SherpaSttEngine.ensureLoaded]
 * без выхода в нативный слой).
 */
class SherpaSttLayoutDetectionTest {

    private val engine = SherpaSttEngine(FakeModelRetentionProvider(keep = false))

    /** Каталог с tokens.txt и заданными файлами (пустое содержимое). */
    private fun tempDir(vararg names: String): File {
        val dir = createTempDirectory("sherpa_layout").toFile()
        File(dir, "tokens.txt").writeText("tok en")
        names.forEach { File(dir, it).writeText("x") }
        return dir
    }

    private fun sttModel(dir: File) = InstalledModel(
        id = "stt-test",
        category = ModelCategory.STT,
        displayName = "STT Test",
        description = "",
        runtime = RuntimeType.SHERPA,
        sizeBytes = 1L,
        ramRequiredMb = 64,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = dir.absolutePath,
        bytesDone = 1L,
        isBuiltin = true
    )

    @Test
    fun `canonical triple is transducer`() {
        val dir = tempDir(
            "zipformer.encoder.onnx", "zipformer.decoder.onnx", "zipformer.joiner.onnx"
        )
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            assertEquals(
                "transducer",
                (d as SherpaSttEngine.SttLayoutDetection.Layout).info.type
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `canonical pair without joiner is whisper regardless of case`() {
        val dir = tempDir("tiny.Encoder.ONNX", "tiny.Decoder.ONNX")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            assertEquals(
                "whisper",
                (d as SherpaSttEngine.SttLayoutDetection.Layout).info.type
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nemo single model bundle is nemo_ctc`() {
        val dir = tempDir("model.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            assertEquals(
                "nemo_ctc",
                (d as SherpaSttEngine.SttLayoutDetection.Layout).info.type
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nemo bundle with decoder-like substrings is not whisper (issue 83)`() {
        // Регрессия прежней эвристики contains(): NeMo CTC с именами
        // model_encoder.onnx/model_decoder.onnx попадал в whisper-ветку.
        val dir = tempDir("model_encoder.onnx", "model_decoder.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            assertEquals(
                "nemo_ctc",
                (d as SherpaSttEngine.SttLayoutDetection.Layout).info.type
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `transducer with joint onnx is ambiguous not whisper (issue 83)`() {
        // Регрессия: набор encoder.onnx/decoder.onnx/joint.onnx раньше
        // классифицировался как whisper (подстроки encoder/decoder).
        val dir = tempDir("encoder.onnx", "decoder.onnx", "joint.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Unknown)
            val unknown = d as SherpaSttEngine.SttLayoutDetection.Unknown
            assertFalse(unknown.missingTokens)
            assertTrue(unknown.onnxFiles.containsAll(listOf("encoder.onnx", "decoder.onnx", "joint.onnx")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `model bundle wins over canonical pair when both present`() {
        // Документированный приоритет (ревью #83): model*.onnx проверяется
        // раньше whisper-ветки.
        val dir = tempDir("model.onnx", "x.encoder.onnx", "x.decoder.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            assertEquals(
                "nemo_ctc",
                (d as SherpaSttEngine.SttLayoutDetection.Layout).info.type
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing tokens txt reported explicitly`() {
        val dir = createTempDirectory("sherpa_layout_no_tokens").toFile()
        File(dir, "model.onnx").writeText("x")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Unknown)
            assertTrue((d as SherpaSttEngine.SttLayoutDetection.Unknown).missingTokens)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ensureLoaded surfaces ambiguous layout with file listing before native`() = runBlocking {
        // Сквозная проверка текста LoadFailed: детект не доходит до нативного
        // sherpa-слоя (на JVM он всё равно недоступен — UnsatisfiedLinkError
        // дал бы другой текст).
        val dir = tempDir("encoder.onnx", "decoder.onnx", "joint.onnx")
        try {
            val error = engine.ensureLoaded(sttModel(dir))
            assertTrue(error is RuntimeError.LoadFailed)
            val reason = (error as RuntimeError.LoadFailed).reason
            assertTrue(reason, reason.contains("joint.onnx"))
            assertTrue(reason, reason.contains("encoder.onnx"))
        } finally {
            dir.deleteRecursively()
        }
    }
}