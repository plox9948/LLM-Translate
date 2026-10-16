package com.plox.llmtranslate

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.plox.llmtranslate.domain.ModelRetentionProvider
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.runtime.IndicTransOnnxRuntime
import com.plox.llmtranslate.domain.runtime.MarianOnnxRuntime
import com.plox.llmtranslate.domain.runtime.SherpaSttEngine
import com.plox.llmtranslate.domain.runtime.SlimtRuntime
import com.plox.llmtranslate.domain.runtime.SttEngine
import com.plox.llmtranslate.domain.runtime.TranslationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * Профилирование RAM на реальном устройстве: измеряет PSS процесса приложения
 * до загрузки, после загрузки, после инференса и после разгрузки модели.
 *
 * Модель выбирается аргументом `-e model <id>`. Ожидает, что файловая модель уже
 * установлена в getExternalFilesDir("models")/<RUNTIME>/<id>/.
 * Результат пишется в logcat с тегом RAMPROF.
 */
@RunWith(AndroidJUnit4::class)
class ModelRamProfileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Поставщик политики удержания моделей (issue #36): профилирование меряет
     * одну модель за прогон, используется прежнее поведение — выгружать.
     */
    private fun retentionUnload() = ModelRetentionProvider { false }

    private data class Target(
        val id: String,
        val runtimeDir: String,
        val category: ModelCategory,
        val runtime: RuntimeType
    )

    private val targets: Map<String, Target> = listOf(
        Target("firefox-bergamot-ru-en", "MARIAN_SLIMT", ModelCategory.TRANSLATION, RuntimeType.MARIAN_SLIMT),
        Target("firefox-bergamot-en-ru", "MARIAN_SLIMT", ModelCategory.TRANSLATION, RuntimeType.MARIAN_SLIMT),
        Target("indictrans2-indic-en-int8", "INDICTRANS_ONNX", ModelCategory.TRANSLATION, RuntimeType.INDICTRANS_ONNX),
        Target("indictrans2-en-indic-int8", "INDICTRANS_ONNX", ModelCategory.TRANSLATION, RuntimeType.INDICTRANS_ONNX),
        Target("stt-ru-gigaam-v3-e2e-ctc", "SHERPA", ModelCategory.STT, RuntimeType.SHERPA),
        Target("stt-en-parakeet-110m", "SHERPA", ModelCategory.STT, RuntimeType.SHERPA),
        Target("stt-ne-indicconformer", "SHERPA", ModelCategory.STT, RuntimeType.SHERPA),
        Target("stt-whisper-small-multilingual", "SHERPA", ModelCategory.STT, RuntimeType.SHERPA)
    ).associateBy { it.id }

    private fun modelDir(runtime: String, modelId: String): File =
        File(context.getExternalFilesDir("models"), "$runtime/$modelId")

    private fun profDir(): File =
        context.getExternalFilesDir("prof") ?: context.filesDir

    private fun marker(name: String) {
        profDir().mkdirs()
        File(profDir(), name).writeText(System.currentTimeMillis().toString())
    }

    private fun readyModel(t: Target, path: String): InstalledModel = InstalledModel(
        id = t.id,
        category = t.category,
        displayName = t.id,
        description = "",
        runtime = t.runtime,
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

    private fun pssMb(): Int {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.totalPss / 1024
    }

    /**
     * Фоновый опрос PSS для пикового значения во время загрузки и инференса.
     */
    private inner class Sampler : Thread() {
        @Volatile
        var stop = false
        val peakMb = AtomicInteger(0)

        init {
            isDaemon = true
        }

        override fun run() {
            while (!stop) {
                val p = pssMb()
                peakMb.updateAndGet { maxOf(it, p) }
                try {
                    sleep(150)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    @Test
    fun ramProfile() {
        val id = InstrumentationRegistry.getArguments().getString("model")
            ?: "firefox-bergamot-ru-en"
        val target = targets[id]
        assertTrue("Неизвестная модель: $id", target != null)
        val t = target!!
        val dir = modelDir(t.runtimeDir, t.id)
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        File(profDir(), "hold.marker").delete()
        File(profDir(), "rel.marker").delete()
        val model = readyModel(t, dir.absolutePath)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        Thread.sleep(1500)
        System.gc()
        Thread.sleep(500)
        System.gc()
        Thread.sleep(500)
        val base = pssMb()

        val engine: Any = when (t.runtime) {
            RuntimeType.MARIAN_ONNX -> MarianOnnxRuntime(retentionUnload())
            RuntimeType.MARIAN_SLIMT -> SlimtRuntime(retentionUnload())
            RuntimeType.INDICTRANS_ONNX -> IndicTransOnnxRuntime(retentionUnload())
            // Дублирующая libonnxruntime из androidTest jniLibs больше не нужна:
            // в APK одна библиотека 1.30.0, патченый libsherpa-onnx-jni требует её же
            // version-node (VERS_1.30.0), загрузка идёт через clinit sherpa.
            else -> SherpaSttEngine(retentionUnload())
        }

        val sampler = Sampler().also { it.start() }

        val loadError = runBlocking {
            when (engine) {
                is TranslationEngine -> engine.ensureLoaded(model)
                else -> (engine as SttEngine).ensureLoaded(model)
            }
        }
        val loaded = pssMb()
        assertTrue("Ошибка загрузки $id: $loadError", loadError == null)

        runBlocking {
            when (engine) {
                is MarianOnnxRuntime, is SlimtRuntime -> {
                    val from = if (id.contains("ru-en")) "ru" else "en"
                    val to = if (from == "ru") "en" else "ru"
                    val text = if (from == "ru") "Привет, как дела?" else "Hello, how are you?"
                    (engine as TranslationEngine).translate(text, from, to)
                }
                is IndicTransOnnxRuntime -> {
                    if (id.contains("indic-en")) {
                        engine.translate("नमस्ते, तपाईं कस्तो हुनुहुन्छ?", "ne", "en")
                    } else {
                        engine.translate("Hello, how are you?", "en", "ne")
                    }
                }
                is SherpaSttEngine -> {
                    val wav = engineWav(t.id)
                    val lang = when {
                        id.contains("whisper") -> "ru"
                        id.contains("ru") -> "ru"
                        id.contains("en") -> "en"
                        else -> null
                    }
                    engine.transcribe(wav, lang)
                }
            }
        }
        val afterInfer = pssMb()

        // Удержание загруженной модели для внешнего профилирования (dumpsys meminfo).
        val holdMs = InstrumentationRegistry.getArguments().getString("hold", "0").toLong()
        if (holdMs > 0) {
            marker("hold.marker")
            Thread.sleep(holdMs)
        }
        val steady = pssMb()

        sampler.stop = true
        sampler.join()

        runBlocking {
            when (engine) {
                is TranslationEngine -> engine.release()
                is SttEngine -> engine.release()
            }
        }
        System.gc()
        Thread.sleep(600)
        System.gc()
        Thread.sleep(600)
        val afterRelease = pssMb()

        val hold2Ms = InstrumentationRegistry.getArguments().getString("hold2", "0").toLong()
        if (hold2Ms > 0) {
            marker("rel.marker")
            Thread.sleep(hold2Ms)
        }

        Log.i(
            "RAMPROF",
            "RESULT id=$id base=$base loaded=$loaded afterInfer=$afterInfer steady=$steady " +
                "peak=${sampler.peakMb.get()} afterRelease=$afterRelease " +
                "deltaLoad=${loaded - base} deltaInfer=${afterInfer - base} " +
                "residual=${afterRelease - base} deviceFreeMb=" +
                "${mi.availMem / 1024 / 1024} deviceTotalMb=${mi.totalMem / 1024 / 1024}"
        )
    }

    /**
     * WAV для инференса STT: реальный образец из test_wavs модели либо синусоида.
     */
    private fun engineWav(id: String): String {
        val real = File(modelDir("SHERPA", id), "test_wavs/0.wav")
        if (real.exists()) return real.absolutePath
        val audioDir = context.getExternalFilesDir("audio") ?: context.filesDir
        val file = File(audioDir, "ramprof_tone.wav")
        val sampleRate = 16000
        val numSamples = sampleRate
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
        return file.absolutePath
    }
}
