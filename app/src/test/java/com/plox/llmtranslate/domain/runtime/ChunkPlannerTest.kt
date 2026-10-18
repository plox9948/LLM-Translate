package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.tokenizer.TokenizerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Тесты плана чанков для движков с ограничением позиций энкодера (issue #63).
 * [tokenCount] — фейковый счётчик «число слов × 2» — проверка упаковки,
 * а не реальной токенизации.
 */
class ChunkPlannerTest {

    /** Фейковый tokenCount: два токена на слово независимо от длины. */
    private fun wordTokens(text: String): Int =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.size * 2

    @Test
    fun `packs consecutive segments into one chunk within budget`() {
        val plan = planChunks("Hello there. Fine thanks. Ok.", ::wordTokens, budget = 100)
        assertEquals(listOf("Hello there. Fine thanks. Ok."), plan.chunks)
        assertTrue(plan.separators.isEmpty())
    }

    @Test
    fun `overbudget packing closes chunk on segment boundary`() {
        // Бюджет 6 токенов = 3 слова: «Hello there.» (2 слова) + «Fine thanks.»
        // (2 слова) = 4 слова = 8 > 6 → разрыв на границе предложений.
        val plan = planChunks("Hello there. Fine thanks. Ok.", ::wordTokens, budget = 6)
        assertEquals(listOf("Hello there.", "Fine thanks. Ok."), plan.chunks)
        assertEquals(listOf(" "), plan.separators)
    }

    @Test
    fun `chunk boundary separator preserves source newline`() {
        val plan = planChunks("Alpha beta.\nGamma delta.", ::wordTokens, budget = 6)
        assertEquals(listOf("Alpha beta.", "Gamma delta."), plan.chunks)
        assertEquals(listOf("\n"), plan.separators)
    }

    @Test
    fun `oversized single segment falls back to word split`() {
        // Бюджет 6 = 3 слова: первый сегмент из 6 слов дорезается по словам
        // кусками по 3 слова; второй сегмент — отдельный чанк на границе «\n».
        val plan = planChunks(
            "alpha beta gamma delta epsilon zeta.\ntail stop end.",
            ::wordTokens,
            budget = 6
        )
        assertEquals(
            listOf("alpha beta gamma", "delta epsilon zeta.", "tail stop end."),
            plan.chunks
        )
        // Внутри дорезки — обычный пробел, на границе сегментов — «\n».
        assertEquals(listOf(" ", "\n"), plan.separators)
        assertEquals(plan.chunks.size - 1, plan.separators.size)
    }

    @Test
    fun `single oversized word longer than budget still emitted as chunk`() {
        val plan = planChunks("a. superdewdupa.", ::wordTokens, budget = 1)
        // «a.» (1 слово = 2 токена) и «superdewdupa.» — каждое слово длиннее
        // бюджета, но хвост слов всегда уходит в чанк.
        assertEquals(2, plan.chunks.size)
        assertEquals(1, plan.separators.size)
    }

    @Test
    fun `single segment returned as single chunk untouched`() {
        val plan = planChunks("a long stretch without terminators", ::wordTokens, budget = 2)
        assertEquals(listOf("a long stretch without terminators"), plan.chunks)
        assertTrue(plan.separators.isEmpty())
    }

    @Test
    fun `budget exactly equal to joined size keeps single chunk`() {
        // 4 слова × 2 = ровно 8.
        val plan = planChunks("aa bb. cc dd.", ::wordTokens, budget = 8)
        assertEquals(listOf("aa bb. cc dd."), plan.chunks)
        assertTrue(plan.separators.isEmpty())
    }

    @Test
    fun `blank text yields single untouched chunk`() {
        val plan = planChunks("   ", ::wordTokens, budget = 4)
        assertEquals(listOf("   "), plan.chunks)
        assertTrue(plan.separators.isEmpty())
    }

    // ── Реальные числа бюджета IndicTrans (issue #63) ──
    //
    // Дублируют приватные константы IndicTransOnnxRuntime: EMBED_POSITION_LIMIT
    // = 258 (строки embed_positions), CHUNK_BUDGET_CAP = 250.

    /**
     * Резолвинг файла модели от рабочего каталога unit-теста: у JUnit-процесса
     * Gradle working dir = app/ — файл выше на уровень; фолбэк из корня.
     */
    private fun locateTokenizerSrc(): File? {
        val rel = "models/files/INDICTRANS_ONNX/indictrans2-en-indic-int8/tokenizer_src.json"
        return listOf(File("../$rel"), File(rel)).firstOrNull { it.isFile }
    }

    @Test
    fun `real en-indic tokenizer yields text budget between 250 and 255`() {
        val file = locateTokenizerSrc()
        assumeTrue("Модель IndicTrans не найдена — пропуск", file != null)

        val tok = TokenizerJson()
        tok.load(file!!.absolutePath)
        assertTrue("tokenizer_src.json не загрузился", tok.isLoaded)

        // Префикс направления кодируется и без хвоста (added-tokens модели).
        val prefixTokens = tok.encode("eng_Latn npi_Deva").size
        assertEquals("языковой префикс en→ne", 2, prefixTokens)
        val eosTokens = if (tok.eosId() != null) 1 else 0
        assertEquals("входной EOS", 1, eosTokens)

        val budget = minOf(250, 258 - prefixTokens - eosTokens)
        assertTrue(
            "бюджет текста $budget вне диапазона 250..255",
            budget in 250..255
        )
        assertEquals("cap должен оставаться ограничивающим", 250, budget)
    }
}
