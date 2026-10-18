package com.plox.llmtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.plox.llmtranslate.domain.ModelRetentionProvider
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.runtime.IndicTransOnnxRuntime
import com.plox.llmtranslate.domain.runtime.RepetitionTrimmer
import com.plox.llmtranslate.domain.runtime.SlimtRuntime
import com.plox.llmtranslate.domain.tokenizer.IndicProcessorLite
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Golden-тест качества IndicTrans2 (EN⇄NE) на реальном устройстве — issue #61.
 *
 * Корпус: androidTest assets `golden/ne_golden.json` (полезная нагрузка тестового APK,
 * читается через InstrumentationRegistry.getInstrumentation().context.assets).
 * Для каждой пары: вывод модели сравнивается с человеческим ref через chrF
 * (пороги среднего по корпусу) плюс проверки-свойства (вырождение декодера,
 * идемпотентность детокенизации, ключевые слова отдельных кейсов).
 *
 * Модели НЕ скачиваются — ожидается, что они уже лежат в
 * getExternalFilesDir("models")/<RUNTIME>/<id>/ (adb push из models/).
 */
@RunWith(AndroidJUnit4::class)
class GoldenCorpusNeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Порог среднего chrF для EN→NE (консервативный, под тюнинг по таблице вывода). */
    private val MIN_CHRF_EN2NE = 0.30

    /** Порог среднего chrF для NE→EN (латиница совпадает строже — порог выше). */
    private val MIN_CHRF_NE2EN = 0.35

    /** Максимальная длина вывода корпусного теста: признаки 128-токенного цикла. */
    private val MAX_CORPUS_OUTPUT_LEN = 180

    /** Максимальная длина вывода цепочки «Озеро» — ожидаемо одно слово. */
    private val MAX_CHAIN_OUTPUT_LEN = 60

    /** Поставщик политики удержания: golden-тесты грузят по одной модели — выгружать. */
    private fun retentionUnload() = ModelRetentionProvider { false }

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

    // --- Golden-корпус (androidTest assets) ---

    @Serializable
    private data class GoldenPair(
        val id: String,
        val domain: String,
        val src: String,
        val ref: String,
    )

    @Serializable
    private data class GoldenCorpus(
        val version: Int,
        val en2ne: List<GoldenPair>,
        val ne2en: List<GoldenPair>,
    )

    /** Читает корпус из assets тестового APK (androidTest/assets). */
    private fun loadCorpus(): GoldenCorpus {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("golden/ne_golden.json")
            .bufferedReader()
            .use { it.readText() }
        return Json { ignoreUnknownKeys = true }
            .decodeFromString(GoldenCorpus.serializer(), json)
    }

    /**
     * Вывод не должен нести следов вырождения декодера: пустой вывод, повтор,
     * не схлопнутый триммером (issue #28/#30), или длина за пределом разумной —
     * признаки 128-токенного цикла декодера.
     */
    private fun assertNoDegeneration(text: String, maxLen: Int = MAX_CORPUS_OUTPUT_LEN) {
        assertTrue("Пустой перевод", text.isNotBlank())
        assertEquals(
            "Остался повтор в выводе: «$text»",
            text,
            RepetitionTrimmer.trim(text),
        )
        assertTrue(
            "Вывод длиннее $maxLen символов (признак цикла декодера): «$text»",
            text.length <= maxLen,
        )
    }

    /**
     * Прогоняет пару через IndicTrans-движок и применяет все попунктные ассерты:
     * свойства вывода, идемпотентность детокенизации. Возвращает вывод и его
     * chrF относительно ref.
     */
    private fun runPair(
        engine: IndicTransOnnxRuntime,
        pair: GoldenPair,
        from: String,
        to: String,
        tgtLangCode: String,
    ): Pair<String, Double> {
        val result = runBlocking { engine.translate(pair.src, from, to) }
        assertTrue("${pair.id}: ошибка перевода: ${result.exceptionOrNull()}", result.isSuccess)
        val output = result.getOrThrow()

        assertNoDegeneration(output)
        // Детокенизация уже применена рантаймом — постобработка идемпотентна
        // («пунктуация приклеена»: повторный пропуск через postprocess не меняется).
        assertEquals(
            "${pair.id}: детокенизация не идемпотентна: «$output»",
            output,
            IndicProcessorLite.postprocess(output, tgtLangCode),
        )
        return output to ChrF.score(output, pair.ref)
    }

    @Test
    fun goldenCorpusEn2Ne() {
        // Самобъятие chrF: идентичные строки дают ровно 1.0.
        assertEquals(
            "chrF самосовпадения должен быть 1.0",
            1.0,
            ChrF.score("नमस्ते छ", "नमस्ते छ"),
            1e-9,
        )

        val dir = modelDir("INDICTRANS_ONNX", "indictrans2-en-indic-int8")
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        val model = readyModel(
            "indictrans2-en-indic-int8", ModelCategory.TRANSLATION,
            RuntimeType.INDICTRANS_ONNX, dir.absolutePath,
        )
        val engine = IndicTransOnnxRuntime(retentionUnload())
        val loadError = runBlocking { engine.ensureLoaded(model) }
        assertTrue("Ошибка загрузки: $loadError", loadError == null)

        val corpus = loadCorpus()
        assertTrue("Корпус en2ne пуст", corpus.en2ne.isNotEmpty())
        val outputs = mutableMapOf<String, String>()
        val scores = mutableListOf<Double>()
        println("chrF EN->NE: id | chrF | вывод")
        for (pair in corpus.en2ne) {
            val (output, f) = runPair(engine, pair, "en", "ne", IndicProcessorLite.NEPALI)
            outputs[pair.id] = output
            scores.add(f)
            println("${pair.id} | ${"%.4f".format(f)} | $output")
        }

        val avg = scores.average()
        println("chrF EN->NE: среднее=${"%.4f".format(avg)} (порог $MIN_CHRF_EN2NE)")
        assertTrue(
            "Средний chrF EN→NE ${"%.4f".format(avg)} ниже порога $MIN_CHRF_EN2NE",
            avg >= MIN_CHRF_EN2NE,
        )

        // Регрессия кейса en2ne_009 (перелёт/рейс): в выводе обязано быть
        // слово «часы» — модель теряла эту сущность при вырождении.
        // Допускаются обе нормативные орфографии: घण्टা (U+0918 U+0923 U+094D U+091F U+093E)
        // и घन\tा-вариант घन्टा (U+0918 U+0928 U+094D U+091F U+093E).
        val race = outputs["en2ne_009"] ?: error("В корпусе нет кейса en2ne_009")
        assertTrue(
            "Регрессия en2ne_009: в выводе нет «часы» (घण्टा/घन्टा): «$race»",
            race.contains("\u0918\u0923\u094D\u091F\u093E") || race.contains("\u0918\u0928\u094D\u091F\u093E"),
        )
        runBlocking { engine.release() }
    }

    @Test
    fun goldenCorpusNe2En() {
        val dir = modelDir("INDICTRANS_ONNX", "indictrans2-indic-en-int8")
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        val model = readyModel(
            "indictrans2-indic-en-int8", ModelCategory.TRANSLATION,
            RuntimeType.INDICTRANS_ONNX, dir.absolutePath,
        )
        val engine = IndicTransOnnxRuntime(retentionUnload())
        val loadError = runBlocking { engine.ensureLoaded(model) }
        assertTrue("Ошибка загрузки: $loadError", loadError == null)

        val corpus = loadCorpus()
        assertTrue("Корпус ne2en пуст", corpus.ne2en.isNotEmpty())
        val outputs = mutableMapOf<String, String>()
        val scores = mutableListOf<Double>()
        println("chrF NE->EN: id | chrF | вывод")
        for (pair in corpus.ne2en) {
            val (output, f) = runPair(engine, pair, "ne", "en", IndicProcessorLite.ENGLISH)
            outputs[pair.id] = output
            scores.add(f)
            println("${pair.id} | ${"%.4f".format(f)} | $output")
        }

        val avg = scores.average()
        println("chrF NE->EN: среднее=${"%.4f".format(avg)} (порог $MIN_CHRF_NE2EN)")
        assertTrue(
            "Средний chrF NE→EN ${"%.4f".format(avg)} ниже порога $MIN_CHRF_NE2EN",
            avg >= MIN_CHRF_NE2EN,
        )

        // Регрессия кейса ne2en_020 (число «५ सय»): в выводе обязано быть «500».
        val number = outputs["ne2en_020"] ?: error("В корпусе нет кейса ne2en_020")
        assertTrue(
            "Регрессия ne2en_020: в выводе нет «500»: «$number»",
            number.contains("500"),
        )
        runBlocking { engine.release() }
    }

    /**
     * Регрессия падения на лимите позиций (issue #63): длинный ne-абзац
     * (заведомо > 258 токенов целиком) обязан переводиться — движок сам
     * режет его чанкерами по границам данды/слов и склеивает результат.
     * До чанкинра давал InvalidArgument (embed_positions idx=258).
     */
    @Test
    fun longNepaliParagraphChunking() {
        val dir = modelDir("INDICTRANS_ONNX", "indictrans2-indic-en-int8")
        assertTrue("Каталог модели не найден: $dir", dir.exists())
        val model = readyModel(
            "indictrans2-indic-en-int8", ModelCategory.TRANSLATION,
            RuntimeType.INDICTRANS_ONNX, dir.absolutePath,
        )
        val engine = IndicTransOnnxRuntime(retentionUnload())
        val loadError = runBlocking { engine.ensureLoaded(model) }
        assertTrue("Ошибка загрузки: $loadError", loadError == null)

        // Абзац из предложений корпуса, склеенных дандой без пробела после
        // (плотная непальская запись) — суммарно заведомо за окном эмбеддингов.
        val corpus = loadCorpus()
        val paragraph = corpus.ne2en.joinToString("।") { it.src.trimEnd('।') } + "।"
        val result = runBlocking { engine.translate(paragraph, "ne", "en") }
        assertTrue("Длинный ne-абзац не переведён: ${result.exceptionOrNull()}", result.isSuccess)
        val out = result.getOrThrow()
        assertNoDegeneration(out, 4000)
        assertTrue("Длинный абзац дал пустой вывод", out.isNotBlank())
        runBlocking { engine.release() }
    }

    /**
     * Цепочечная регрессия скриншот-бага «Озеро»: RU→EN (slimt) -> EN→NE
     * (IndicTrans2), два вызова напрямую без ViewModel (цепочка как в
     * TranslateViewModel). Финальный NE-вывод обязан быть коротким однословным
     * словом с корнем «озеро» — 128-токенный цикл IndicTrans на «lake» давал
     * длинный вырожденный текст.
     *
     * SKIPPED, если slimt-модели нет на устройстве.
     */
    @Test
    fun chainRegressionLake() {
        val slimtDir = modelDir("MARIAN_SLIMT", "firefox-bergamot-ru-en")
        assumeTrue("Нет slimt-модели $slimtDir — цепочечный тест пропущен", slimtDir.exists())

        val indicDir = modelDir("INDICTRANS_ONNX", "indictrans2-en-indic-int8")
        assertTrue("Каталог модели не найден: $indicDir", indicDir.exists())

        val lakeTokens = listOf("ताल", "हिमताल", "जिल")

        // Этап 1: RU→EN через slimt. Ошибку/пусто не оборачиваем — берём как есть,
        // дальше ассерты по финальному выводу поймают регрессию.
        val stage1Inputs = listOf("Озеро", "Озеро.")
        val stage1Outputs = mutableListOf<Pair<String, String>>()
        run {
            val slimtModel = readyModel(
                "firefox-bergamot-ru-en", ModelCategory.TRANSLATION,
                RuntimeType.MARIAN_SLIMT, slimtDir.absolutePath,
            )
            val slimt = SlimtRuntime(retentionUnload())
            val loadError = runBlocking { slimt.ensureLoaded(slimtModel) }
            assertTrue("Ошибка загрузки slimt: $loadError", loadError == null)
            for (input in stage1Inputs) {
                val result = runBlocking { slimt.translate(input, "ru", "en") }
                val mid = result.getOrNull().orEmpty()
                if (result.isFailure) {
                    println("CHAIN «$input» → EN: ошибка ${result.exceptionOrNull()}, берём как есть")
                }
                println("CHAIN «$input» → EN: «$mid»")
                stage1Outputs.add(input to mid)
            }
            runBlocking { slimt.release() }
        }

        // Этап 2: EN→NE через IndicTrans2 последовательно после выгрузки slimt.
        val indicModel = readyModel(
            "indictrans2-en-indic-int8", ModelCategory.TRANSLATION,
            RuntimeType.INDICTRANS_ONNX, indicDir.absolutePath,
        )
        val indic = IndicTransOnnxRuntime(retentionUnload())
        val loadError = runBlocking { indic.ensureLoaded(indicModel) }
        assertTrue("Ошибка загрузки IndicTrans: $loadError", loadError == null)
        for ((input, mid) in stage1Outputs) {
            val result = runBlocking { indic.translate(mid, "en", "ne") }
            assertTrue("CHAIN «$input»: ошибка EN→NE: ${result.exceptionOrNull()}", result.isSuccess)
            val ne = result.getOrThrow()
            println("CHAIN «$input» → NE: «$ne»")
            assertNoDegeneration(ne, MAX_CHAIN_OUTPUT_LEN)
            assertTrue(
                "CHAIN «$input»: в NE-выводе нет ни одного маркера «озеро» " +
                    "($lakeTokens): «$ne»",
                lakeTokens.any { ne.contains(it) },
            )
        }
        runBlocking { indic.release() }
    }
}
