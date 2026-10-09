package com.plox.llmtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.runtime.IndicTransOnnxRuntime
import com.plox.llmtranslate.domain.runtime.MarianOnnxRuntime
import com.plox.llmtranslate.domain.runtime.SherpaSttEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Smoke-тест инференса на реальном устройстве.
 *
 * Ожидает, что модели уже загружены в каталог getExternalFilesDir("models")/<RUNTIME>/<id>/
 * (через adb push из models/). Тест НЕ скачивает модели — только проверяет инференс.
 */
@RunWith(AndroidJUnit4::class)
class InferenceSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun modelDir(runtime: String, modelId: String): File =
        File(context.getExternalFilesDir("models"), "$runtime/$modelId")

    private fun readyModel(
        id: String,
        category: ModelCategory,
        runtime: RuntimeType,
        path: String
    ): InstalledModel = InstalledModel(
        id = id,
        category = category,
        displayName = id,
        description = "",
        runtime = runtime,
        sizeBytes = 0,
        ramRequiredMb = 0,
        languages = emptyList(),
        quality = 3,
        speed = 3,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = path,
        bytesDone = 0,
        isBuiltin = true
    )

    @Test
    fun marianRuEnTranslation() {
        val dir = modelDir("MARIAN_ONNX", "opus-mt-tiny-ru-en-int8-v2")
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        val model = readyModel(
            "opus-mt-tiny-ru-en-int8-v2", ModelCategory.TRANSLATION,
            RuntimeType.MARIAN_ONNX, dir.absolutePath
        )
        val engine = MarianOnnxRuntime()
        val error = runBlocking { engine.ensureLoaded(model) }
        assertNull("ensureLoaded вернул ошибку: $error", error)

        val result = runBlocking { engine.translate("Привет, как дела?", "ru", "en") }
        assertTrue("Ошибка перевода: ${result.exceptionOrNull()}", result.isSuccess)
        val text = result.getOrThrow()
        println("MARIAN RU->EN: «Привет, как дела?» => $text")
        assertTrue("Пустой перевод", text.isNotBlank())
        runBlocking { engine.release() }
    }

    @Test
    fun marianEnRuTranslation() {
        val dir = modelDir("MARIAN_ONNX", "opus-mt-tiny-en-ru-int8-v2")
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        val model = readyModel(
            "opus-mt-tiny-en-ru-int8-v2", ModelCategory.TRANSLATION,
            RuntimeType.MARIAN_ONNX, dir.absolutePath
        )
        val engine = MarianOnnxRuntime()
        val error = runBlocking { engine.ensureLoaded(model) }
        assertTrue("Ошибка загрузки: $error", error == null)

        val result = runBlocking { engine.translate("Hello, how are you?", "en", "ru") }
        assertTrue("Ошибка перевода: ${result.exceptionOrNull()}", result.isSuccess)
        val text = result.getOrThrow()
        println("MARIAN EN->RU: «Hello, how are you?» => $text")
        assertTrue("Пустой перевод", text.isNotBlank())
        runBlocking { engine.release() }
    }

    @Test
    fun indicTransNeEnTranslation() {
        val dir = modelDir("INDICTRANS_ONNX", "indictrans2-indic-en-int8")
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        val model = readyModel(
            "indictrans2-indic-en-int8", ModelCategory.TRANSLATION,
            RuntimeType.INDICTRANS_ONNX, dir.absolutePath
        )
        val engine = IndicTransOnnxRuntime()
        val error = runBlocking { engine.ensureLoaded(model) }
        assertTrue("Ошибка загрузки: $error", error == null)

        // Непальский текст (Деванагари).
        val neText = "नमस्ते, तपाईं कस्तो हुनुहुन्छ?"
        val result = runBlocking { engine.translate(neText, "ne", "en") }
        assertTrue("Ошибка перевода: ${result.exceptionOrNull()}", result.isSuccess)
        val text = result.getOrThrow()
        println("INDICTRANS NE->EN: «$neText» => $text")
        assertTrue("Пустой перевод", text.isNotBlank())
        runBlocking { engine.release() }
    }

    @Test
    fun sherpaParakeetSttLoadsAndRuns() {
        val dir = modelDir("SHERPA", "stt-en-parakeet-110m")
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        val model = readyModel(
            "stt-en-parakeet-110m", ModelCategory.STT,
            RuntimeType.SHERPA, dir.absolutePath
        )
        val engine = SherpaSttEngine()
        val error = runBlocking { engine.ensureLoaded(model) }
        assertTrue("Ошибка загрузки sherpa-модели: $error", error == null)

        // Генерируем тестовый WAV (синусоида) — проверка пайплайна без реальной речи.
        val wavFile = writeTestWav()
        val result = runBlocking { engine.transcribe(wavFile.absolutePath, language = "en") }
        println("SHERPA PARAKEET STT: success=${result.isSuccess}, text='${result.getOrNull()}'")
        // Главное — модель загрузилась и пайплайн не упал.
        runBlocking { engine.release() }
    }

    @Test
    fun sherpaZipformerSttLoads() {
        val dir = modelDir("SHERPA", "stt-ru-zipformer-2024-09-18")
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        val model = readyModel(
            "stt-ru-zipformer-2024-09-18", ModelCategory.STT,
            RuntimeType.SHERPA, dir.absolutePath
        )
        val engine = SherpaSttEngine()
        val error = runBlocking { engine.ensureLoaded(model) }
        assertTrue("Ошибка загрузки sherpa-модели (transducer): $error", error == null)
        runBlocking { engine.release() }
    }

    /**
     * Записать тестовый WAV (синусоида 440 Гц, 16 kHz, mono, 16-bit).
     */
    private fun writeTestWav(): File {
        val audioDir = context.getExternalFilesDir("audio") ?: context.filesDir
        val file = File(audioDir, "test_tone.wav")
        val sampleRate = 16000
        val duration = 1.0
        val numSamples = (sampleRate * duration).toInt()
        val pcm = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val sample = (sin(2 * PI * 440 * i / sampleRate) * 16000).toInt().toShort()
            val le = java.nio.ByteBuffer.allocate(2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putShort(sample)
                .array()
            pcm[i * 2] = le[0]
            pcm[i * 2 + 1] = le[1]
        }
        com.plox.llmtranslate.domain.runtime.WavWriter.writeWav(file, pcm, sampleRate, 1, 16)
        return file
    }
}
