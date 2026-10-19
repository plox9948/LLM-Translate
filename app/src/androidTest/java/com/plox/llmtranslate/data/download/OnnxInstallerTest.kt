package com.plox.llmtranslate.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Instrumentation-тесты [OnnxInstaller] на устройстве без сети и без реальных
 * ONNX-моделей: фикстуры — псевдо-ONNX байты, которые раздаёт
 * [LocalTestHttpServer] на loopback. Инсталлятор файл модели не открывает —
 * только скачивает, сверяет SHA-256 и переименовывает, поэтому содержимое
 * фикстуры произвольно.
 *
 * Ключевая проверяемая инвариантность (замечание ревьюера): индекс массива
 * sha256s жёстко привязан к индексу url/файла — «перевязка» хешей обязана
 * давать mismatch на файле, который стоит под этим индексом.
 */
@RunWith(AndroidJUnit4::class)
class OnnxInstallerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Псевдо-ONNX фикстуры: разное содержимое — разные SHA-256.
    private val encoderBytes = "pseudo-onnx-encoder-model-bytes".toByteArray()
    private val decoderBytes = "pseudo-onnx-decoder-model-bytes-longer!".toByteArray()
    private val tokenizerBytes = "{\"type\":\"HuggingFace\",\"vocab\":[]}".toByteArray()

    private lateinit var targetDir: File
    private var server: LocalTestHttpServer? = null

    @Before
    fun setUp() {
        targetDir = File(context.cacheDir, "onnx_installer_test")
        targetDir.deleteRecursively()
        targetDir.mkdirs()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        targetDir.deleteRecursively()
    }

    private fun startServer(files: Map<String, ByteArray>): LocalTestHttpServer {
        val s = LocalTestHttpServer(files)
        server = s
        return s
    }

    /** SHA-256 фикстуры, посчитанный на устройстве (тот же алгоритм, что в BaseDownloader). */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun multiFileModelInstallsWithPerIndexSha256sKeepsLinearMapping() {
        val http = startServer(
            mapOf(
                "/encoder_model.onnx" to encoderBytes,
                "/decoder_model.onnx" to decoderBytes,
                "/tokenizer.json" to tokenizerBytes
            )
        )
        val urls = listOf(
            "${http.baseUrl}/encoder_model.onnx",
            "${http.baseUrl}/decoder_model.onnx",
            "${http.baseUrl}/tokenizer.json"
        )
        val sha256s = listOf(sha256(encoderBytes), sha256(decoderBytes), sha256(tokenizerBytes))

        val result = runBlocking {
            OnnxInstaller().install(urls, null, sha256s, targetDir, "fixture-model") {}
        }

        assertTrue("Ожидался Success, получено: $result", result is InstallResult.Success)
        val modelDir = File(targetDir, "fixture-model")
        assertEquals(modelDir.absolutePath, (result as InstallResult.Success).path)

        // Содержимое под индексом url[0] — именно encoder-фикстура: байты
        // совпадают, значит файл скачан по своему URL и проверен своим хешем.
        assertArrayEqualsSafe(encoderBytes, File(modelDir, "encoder_model.onnx").readBytes())
        assertArrayEqualsSafe(decoderBytes, File(modelDir, "decoder_model.onnx").readBytes())
        assertArrayEqualsSafe(tokenizerBytes, File(modelDir, "tokenizer.json").readBytes())

        // .part и .part.meta не остаются после успешной установки.
        assertEquals(
            "Остались служебные файлы: ${modelDir.list()?.joinToString()}",
            3,
            modelDir.listFiles()?.size
        )
    }

    @Test
    fun wrongSha256ForOneIndexFailsPermanentWithFileName() {
        val http = startServer(
            mapOf(
                "/encoder_model.onnx" to encoderBytes,
                "/decoder_model.onnx" to decoderBytes
            )
        )
        val urls = listOf(
            "${http.baseUrl}/encoder_model.onnx",
            "${http.baseUrl}/decoder_model.onnx"
        )
        // Хеш второго файла намеренно неверен.
        val sha256s = listOf(sha256(encoderBytes), "deadbeef".repeat(8))

        val result = runBlocking {
            OnnxInstaller().install(urls, null, sha256s, targetDir, "fixture-model") {}
        }

        assertTrue("Ожидался Failure, получено: $result", result is InstallResult.Failure)
        val failure = result as InstallResult.Failure
        assertTrue(
            "В сообщении должен быть имя битого файла: ${failure.message}",
            failure.message.contains("decoder_model.onnx")
        )
        // Mismatch после СВЕЖЕЙ загрузки (без .part) — повтор бессмысленен.
        assertTrue("SHA-mismatch свежей загрузки обязан быть permanent", failure.permanent)
        // Битый файл не остаётся в каталоге модели.
        assertFalse(File(targetDir, "fixture-model/decoder_model.onnx").exists())
    }

    @Test
    fun swappedSha256sMismatchOnFileAtUrlIndex() {
        // Перевязка хешей: url[0]=encoder, но sha256s[0] — хеш decoder-байтов.
        // Индексная привязка обязана отбраковать encoder_model.onnx, хотя сам
        // encoder-файл на сервере корректен относительно своего содержимого.
        val http = startServer(
            mapOf(
                "/encoder_model.onnx" to encoderBytes,
                "/decoder_model.onnx" to decoderBytes
            )
        )
        val urls = listOf(
            "${http.baseUrl}/encoder_model.onnx",
            "${http.baseUrl}/decoder_model.onnx"
        )
        val sha256s = listOf(sha256(decoderBytes), sha256(encoderBytes))

        val result = runBlocking {
            OnnxInstaller().install(urls, null, sha256s, targetDir, "fixture-model") {}
        }

        val failure = result as? InstallResult.Failure
        assertTrue("Ожидался Failure по первому файлу, получено: $result", failure != null)
        assertTrue(
            "Mismatch обязан быть по encoder_model.onnx (индекс 0): ${failure!!.message}",
            failure.message.contains("encoder_model.onnx")
        )
    }

    @Test
    fun nullSha256ElementSkipsCheckForThatIndexOnly() {
        val http = startServer(
            mapOf(
                "/encoder_model.onnx" to encoderBytes,
                "/decoder_model.onnx" to decoderBytes
            )
        )
        val urls = listOf(
            "${http.baseUrl}/encoder_model.onnx",
            "${http.baseUrl}/decoder_model.onnx"
        )
        // Первый файл проверяется своим хешем, второй — без проверки (null).
        val sha256s = listOf(sha256(encoderBytes), null)

        val result = runBlocking {
            OnnxInstaller().install(urls, null, sha256s, targetDir, "fixture-model") {}
        }

        assertTrue("Ожидался Success, получено: $result", result is InstallResult.Success)
        assertArrayEqualsSafe(
            decoderBytes,
            File(targetDir, "fixture-model/decoder_model.onnx").readBytes()
        )
    }

    @Test
    fun singleUrlValidatedByItsSha256sEntry() {
        val http = startServer(mapOf("/model.onnx" to encoderBytes))
        val urls = listOf("${http.baseUrl}/model.onnx")

        val ok = runBlocking {
            OnnxInstaller().install(urls, null, listOf(sha256(encoderBytes)), targetDir, "single-model") {}
        }
        assertTrue("Ожидался Success, получено: $ok", ok is InstallResult.Success)
        // Одиночный файл скачивается в корень каталога с префиксом id
        // (единая схема DownloadLayout.targetFileName, ревью п.15).
        assertArrayEqualsSafe(
            encoderBytes,
            File(targetDir, "single-model__model.onnx").readBytes()
        )

        val bad = runBlocking {
            OnnxInstaller().install(urls, null, listOf("00".repeat(32)), targetDir, "single-model") {}
        }
        val failure = bad as? InstallResult.Failure
        assertTrue("Ожидался Failure по неверному хешу, получено: $bad", failure != null)
        assertTrue("Свежий mismatch должен быть permanent", failure!!.permanent)
    }

    @Test
    fun legacySingleSha256ParameterAppliesToFirstUrl() {
        // Старые строки БД/импорт: sha256s пуст, единственный sha256.
        val http = startServer(mapOf("/model.onnx" to encoderBytes))
        val result = runBlocking {
            OnnxInstaller().install(
                listOf("${http.baseUrl}/model.onnx"),
                sha256(encoderBytes),
                emptyList(),
                targetDir,
                "legacy-model"
            ) {}
        }
        assertTrue("Ожидался Success, получено: $result", result is InstallResult.Success)
    }

    @Test
    fun emptyUrlsListFailsWithoutNetwork() {
        val result = runBlocking {
            OnnxInstaller().install(emptyList(), null, emptyList(), targetDir, "no-urls") {}
        }
        assertTrue("Ожидался Failure, получено: $result", result is InstallResult.Failure)
    }

    private fun assertArrayEqualsSafe(expected: ByteArray, actual: ByteArray) {
        assertEquals("Содержимое файла не совпало с фикстурой", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("Байт $i не совпал", expected[i], actual[i])
        }
    }
}
