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
 * transducer/whisper распознаются только по каноническим sherpa-шаблонам
 * имён ролей с возможным инфиксом квантизации (фактические int8-имена
 * манифеста models/manifest.json), NeMo/piper-подобные `model*.onnx` имеют
 * приоритет над whisper-веткой и выбираются детерминированно, при
 * неоднозначности — явная ошибка с перечислением файлов (и на уровне
 * [SherpaSttEngine.detectModel], и сквозной проверкой в
 * [SherpaSttEngine.ensureLoaded] без выхода в нативный слой).
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

    // ── регрессия #83 (замечание 1): int8-имена фактических пучков манифеста ──

    @Test
    fun `manifest whisper int8 pair is whisper`() {
        // Файлы stt-whisper-small-multilingual из models/manifest.json:
        // строгие суффиксы .encoder.onnx/.decoder.onnx их не распознавали —
        // LoadFailed на уже установленных моделях.
        val dir = tempDir("encoder.int8.onnx", "decoder.int8.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            val layout = d as SherpaSttEngine.SttLayoutDetection.Layout
            assertEquals("whisper", layout.info.type)
            assertTrue(layout.info.encoderPath!!.endsWith("encoder.int8.onnx"))
            assertTrue(layout.info.decoderPath!!.endsWith("decoder.int8.onnx"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `manifest nemo int8 single bundle is nemo_ctc`() {
        // Файл stt-ne-indicconformer из манифеста: model.int8.onnx.
        val dir = tempDir("model.int8.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            val layout = d as SherpaSttEngine.SttLayoutDetection.Layout
            assertEquals("nemo_ctc", layout.info.type)
            assertTrue(layout.info.nemoModelPath!!.endsWith("model.int8.onnx"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `int8 transducer triple is transducer`() {
        val dir = tempDir(
            "zipformer.encoder.int8.onnx",
            "zipformer.decoder.int8.onnx",
            "zipformer.joiner.int8.onnx"
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

    // ── замечание 6: детерминированный выбор NeMo-пучка ──

    @Test
    fun `nemo selection prefers exact canonical model names`() {
        val dir = tempDir("model_b.onnx", "model_a.onnx", "model.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            val layout = d as SherpaSttEngine.SttLayoutDetection.Layout
            assertEquals("nemo_ctc", layout.info.type)
            assertEquals(
                File(dir, "model.onnx").absolutePath,
                layout.info.nemoModelPath
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nemo fallback selection is sorted by name`() {
        val dir = tempDir("modelzz.onnx", "modelaa.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Layout)
            val layout = d as SherpaSttEngine.SttLayoutDetection.Layout
            assertEquals("nemo_ctc", layout.info.type)
            assertEquals(
                File(dir, "modelaa.onnx").absolutePath,
                layout.info.nemoModelPath
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
    fun `bundle without decoder role is ambiguous not whisper (issue 83)`() {
        // Набор без канонической роли decoder (decoder_model.onnx — имя в
        // стиле IndicTrans, инфикс обязан начинаться с точки) и с joint.onnx:
        // ни одна ветка не подходит — явная ошибка с перечислением файлов.
        val dir = tempDir("encoder.onnx", "decoder_model.onnx", "joint.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Unknown)
            val unknown = d as SherpaSttEngine.SttLayoutDetection.Unknown
            assertFalse(unknown.missingTokens)
            assertTrue(
                unknown.onnxFiles.containsAll(
                    listOf("encoder.onnx", "decoder_model.onnx", "joint.onnx")
                )
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `indictrans-style role-underscore names are not whisper roles`() {
        // encoder_model.onnx/decoder_model.onnx (Optimum-экспорт IndicTrans2)
        // ролевыми sherpa-шаблонами не считаются — Uknown вместо угадывания.
        val dir = tempDir("encoder_model.onnx", "decoder_model.onnx")
        try {
            val d = engine.detectModel(dir)
            assertTrue(d is SherpaSttEngine.SttLayoutDetection.Unknown)
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
        val dir = tempDir("encoder.onnx", "decoder_model.onnx", "joint.onnx")
        try {
            val error = engine.ensureLoaded(sttModel(dir))
            assertTrue(error is RuntimeError.LoadFailed)
            val reason = (error as RuntimeError.LoadFailed).reason
            assertTrue(reason, reason.contains("joint.onnx"))
            assertTrue(reason, reason.contains("decoder_model.onnx"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
