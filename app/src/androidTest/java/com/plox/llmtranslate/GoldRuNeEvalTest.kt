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
import com.plox.llmtranslate.domain.runtime.TranslationEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import timber.log.Timber

/**
 * Eval-харнесc качества цепочек RU⇄NE на устройстве — BASELINE до фикса.
 *
 * Корпус: androidTest assets `golden/gold_ru_ne.csv` (строки `id;русский текст`).
 * Для каждой фразы прогоняются обе цепочки:
 * RU→EN (slimt, firefox-bergamot-ru-en) → EN→NE (IndicTrans2, indictrans2-en-indic-int8)
 * и обратно NE→EN (IndicTrans2, indictrans2-indic-en-int8) → EN→RU (slimt,
 * firefox-bergamot-en-ru). Движки загружаются/выгружаются последовательно
 * (retentionUnload — как в GoldenCorpusNeTest), пучки НЕ скачиваются —
 * ожидается adb push в getExternalFilesDir("models").
 *
 * Харнесc собирает данные, а не ассертит качество: ошибка перевода по фразе
 * не валит тест, а пишется в ячейку «ОШИБКА: …»; каждый вывод помечается
 * признаками вырождения (повтор по RepetitionTrimmer, длина > 180 символов).
 * Для round-trip считается chrF между RU-back и исходным RU.
 *
 * Результат — markdown-отчёт getExternalFilesDir("eval")/gold_ru_ne_report.md;
 * единственный ассерт — файл записан и непуст. Каждая фраза дублируется
 * в println (для logcat).
 */
/** Префикс ячейки сбоем перевода. */
private const val ERROR_PREFIX = "ОШИБКА:"

@RunWith(AndroidJUnit4::class)
class GoldRuNeEvalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Признак цикла декодера: вывод длиннее этого числа символов. */
    private val MAX_EVAL_OUTPUT_LEN = 180

    /** Поставщик политики удержания: харнесc грузит по одной модели — выгружать. */
    private fun retentionUnload() = ModelRetentionProvider { false }

    private fun modelDir(runtime: String, modelId: String): File =
        File(context.getExternalFilesDir("models"), "$runtime/$modelId")

    /** Пропустить харнесc, если пучок модели не установлен на устройстве. */
    private fun requireModelDir(dir: File) {
        assumeTrue("Модель не установлена на устройстве: $dir", dir.exists())
    }

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

    /**
     * Timber-логи движка («IndicTrans: схлопнут повтор …») уходят в logcat
     * только при посаженном дереве — сажаем debug-дерево на время харнесcа.
     */
    @Before
    fun plantTimber() {
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
    }

    @After
    fun uprootTimber() {
        for (tree in Timber.forest()) Timber.uproot(tree)
    }

    // --- Корпус и ячейки отчёта ---

    private data class CorpusRow(val id: String, val ru: String)
    /** Ячейка харнесcа: вывод стадии (или «ОШИБКА: …») + признаки вырождения. */
    private class Cell {
        var value: String = ""
        val signs: MutableList<String> = mutableListOf()
        val isCellError: Boolean get() = value.startsWith(ERROR_PREFIX)
    }

    /** Читает корпус из assets тестового APK: строки `id;русский текст`. */
    private fun loadCorpus(): List<CorpusRow> {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("golden/gold_ru_ne.csv")
            .bufferedReader()
            .use { it.readText() }
        return text.lineSequence()
            .map { it.trimEnd() }
            .filter { it.contains(';') }
            .map { line ->
                val sep = line.indexOf(';')
                CorpusRow(line.substring(0, sep).trim(), line.substring(sep + 1))
            }
            .toList()
    }

    /** Признаки вырождения вывода: несхлопнутый повтор и длина за пределом. */
    private fun degenerationSigns(text: String): List<String> {
        val signs = mutableListOf<String>()
        if (RepetitionTrimmer.trim(text) != text) signs.add("повтор")
        if (text.length > MAX_EVAL_OUTPUT_LEN) signs.add("длина")
        return signs
    }

    /**
     * Прогоняет все фразы стадии через движок [engine] (уже загружен) и
     * заполняет [cells]: вывод либо «ОШИБКА: …». Пустой/сбойный вход
     * предыдущей стадии не отправляется в движок. Каждая фраза — в println.
     */
    private fun runStage(
        engine: TranslationEngine,
        rows: List<CorpusRow>,
        inputs: Map<String, String>,
        from: String,
        to: String,
        cells: Map<String, Cell>,
    ) {
        for (row in rows) {
            val cell = cells.getValue(row.id)
            val input = inputs.getValue(row.id)
            if (input.isBlank()) {
                cell.value = "$ERROR_PREFIX пустой вход стадии $from→$to"
                println("EVAL ${row.id} $from→$to: ${cell.value}")
                continue
            }
            val result = runBlocking { engine.translate(input, from, to) }
            if (result.isFailure) {
                cell.value = "$ERROR_PREFIX ${result.exceptionOrNull()?.message}"
            } else {
                val out = result.getOrThrow()
                cell.value = out
                cell.signs.addAll(degenerationSigns(out))
            }
            println("EVAL ${row.id} $from→$to: ${cell.value}")
        }
    }

    /**
     * Загружает движок [engine] с пучком [modelId] из каталога [dir],
     * выполняет [body] на том же движке и всегда выгружает его
     * (как в GoldenCorpusNeTest).
     */
    private fun withEngine(
        engine: TranslationEngine,
        modelId: String,
        runtime: RuntimeType,
        dir: File,
        body: (TranslationEngine) -> Unit,
    ) {
        val model = readyModel(modelId, ModelCategory.TRANSLATION, runtime, dir.absolutePath)
        val loadError = runBlocking { engine.ensureLoaded(model) }
        assertTrue("$modelId: ошибка загрузки: $loadError", loadError == null)
        try {
            body(engine)
        } finally {
            runBlocking { engine.release() }
        }
    }

    /** Markdown-ячейка таблицы: pipe и переводы строк ломают верстку отчёта. */
    private fun escapeCell(text: String): String =
        text.replace("|", "\\|").replace("\n", " ").replace("\r", " ").trim()

    @Test
    fun evalRuNeRoundTrip() {
        val slimtRuEnDir = modelDir("MARIAN_SLIMT", "firefox-bergamot-ru-en")
        val slimtEnRuDir = modelDir("MARIAN_SLIMT", "firefox-bergamot-en-ru")
        val indicEnNeDir = modelDir("INDICTRANS_ONNX", "indictrans2-en-indic-int8")
        val indicNeEnDir = modelDir("INDICTRANS_ONNX", "indictrans2-indic-en-int8")
        // Каталоги пучков создаёт само приложение: adb push не даёт им права
        // на чтение для процесса приложения (shell-владелец каталога).
        for (dir in listOf(slimtRuEnDir, slimtEnRuDir, indicEnNeDir, indicNeEnDir)) dir.mkdirs()
        requireModelDir(slimtRuEnDir)
        requireModelDir(slimtEnRuDir)
        requireModelDir(indicEnNeDir)
        requireModelDir(indicNeEnDir)

        val rows = loadCorpus()
        assertTrue("Корпус gold_ru_ne.csv пуст", rows.isNotEmpty())

        val enCells = rows.associate { it.id to Cell() }
        val neCells = rows.associate { it.id to Cell() }
        val enBackCells = rows.associate { it.id to Cell() }
        val ruBackCells = rows.associate { it.id to Cell() }

        // Стадия 1: RU→EN (slimt, Bergamot ruen).
        withEngine(SlimtRuntime(retentionUnload()), "firefox-bergamot-ru-en",
            RuntimeType.MARIAN_SLIMT, slimtRuEnDir) { engine ->
            runStage(
                engine, rows, rows.associate { it.id to it.ru }, "ru", "en", enCells,
            )
        }
        // Стадия 2: EN→NE (IndicTrans2 en-indic).
        withEngine(IndicTransOnnxRuntime(retentionUnload()), "indictrans2-en-indic-int8",
            RuntimeType.INDICTRANS_ONNX, indicEnNeDir) { engine ->
            runStage(
                engine, rows,
                enCells.mapValues { (_, c) -> if (c.isCellError) "" else c.value },
                "en", "ne", neCells,
            )
        }
        // Стадия 3: NE→EN (IndicTrans2 indic-en).
        withEngine(IndicTransOnnxRuntime(retentionUnload()), "indictrans2-indic-en-int8",
            RuntimeType.INDICTRANS_ONNX, indicNeEnDir) { engine ->
            runStage(
                engine, rows,
                neCells.mapValues { (_, c) -> if (c.isCellError) "" else c.value },
                "ne", "en", enBackCells,
            )
        }
        // Стадия 4: EN→RU (slimt, Bergamot enru).
        withEngine(SlimtRuntime(retentionUnload()), "firefox-bergamot-en-ru",
            RuntimeType.MARIAN_SLIMT, slimtEnRuDir) { engine ->
            runStage(
                engine, rows,
                enBackCells.mapValues { (_, c) -> if (c.isCellError) "" else c.value },
                "en", "ru", ruBackCells,
            )
        }

        // Round-trip chrF: RU-back против исходного RU (сбойный back — 0.0).
        val chrFBacks = rows.map { row ->
            val back = ruBackCells.getValue(row.id)
            if (back.isCellError) 0.0 else ChrF.score(back.value, row.ru)
        }
        val avgChrF = chrFBacks.average()

        // Markdown-отчёт во внешнем каталоге eval.
        val evalDir = context.getExternalFilesDir("eval")!!
        evalDir.mkdirs()
        val report = File(evalDir, "gold_ru_ne_report.md")
        val sb = StringBuilder()
        sb.appendLine("# Отчёт RU⇄NE (baseline до фикса)")
        sb.appendLine()
        sb.appendLine(
            "Цепочки: RU→EN firefox-bergamot-ru-en → EN→NE indictrans2-en-indic-int8; " +
                "NE→EN indictrans2-indic-en-int8 → EN→RU firefox-bergamot-en-ru."
        )
        sb.appendLine()
        sb.appendLine("| id | RU | EN (промежуток) | NE | EN-back | RU-back | chrF-back | признаки |")
        sb.appendLine("|---|---|---|---|---|---|---|---|")
        rows.forEachIndexed { index, row ->
            val signs = buildString {
                val en = enCells.getValue(row.id)
                val ne = neCells.getValue(row.id)
                val enBack = enBackCells.getValue(row.id)
                val ruBack = ruBackCells.getValue(row.id)
                if (en.isCellError) append("EN:ошибка ") else if (en.signs.isNotEmpty()) append("EN:${en.signs.joinToString(",")} ")
                if (ne.isCellError) append("NE:ошибка ") else if (ne.signs.isNotEmpty()) append("NE:${ne.signs.joinToString(",")} ")
                if (enBack.isCellError) append("ENback:ошибка ") else if (enBack.signs.isNotEmpty()) append("ENback:${enBack.signs.joinToString(",")} ")
                if (ruBack.isCellError) append("RUback:ошибка ") else if (ruBack.signs.isNotEmpty()) append("RUback:${ruBack.signs.joinToString(",")} ")
            }.trim()
            sb.appendLine(
                "| ${row.id} | ${escapeCell(row.ru)} | ${escapeCell(enCells.getValue(row.id).value)} | " +
                    "${escapeCell(neCells.getValue(row.id).value)} | ${escapeCell(enBackCells.getValue(row.id).value)} | " +
                    "${escapeCell(ruBackCells.getValue(row.id).value)} | ${"%.4f".format(chrFBacks[index])} | ${if (signs.isEmpty()) "—" else signs} |"
            )
        }
        sb.appendLine()
        sb.appendLine("Средний chrF-back: ${"%.4f".format(avgChrF)} (${rows.size} фраз)")
        report.writeText(sb.toString(), Charsets.UTF_8)
        println("EVAL: отчёт записан: ${report.absolutePath}")
        println("EVAL: среднее chrF-back=${"%.4f".format(avgChrF)}")

        assertTrue("Отчёт не записан: ${report.absolutePath}", report.exists())
        assertTrue("Отчёт пуст", report.readText().isNotBlank())
        assertTrue("В отчёте нет строк корпуса", report.readText().contains("| ${rows.first().id} |"))
    }
}
