package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.tokenizer.TokenizerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Тесты плана чанков для движков с ограничением позиций энкодера (issue #63).
 * [tokenCount] — фейковый счётчик «число слов × 2» — проверка
 * предложеничнанкинга (1 предложение = 1 чанк) и дорезки сверхбюджетных
 * сегментов, а не реальной токенизации.
 */
class ChunkPlannerTest {

    /** Фейковый tokenCount: два токена на слово независимо от длины. */
    private fun wordTokens(text: String): Int =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.size * 2

    @Test
    fun `every sentence becomes its own chunk within budget`() {
        // Многосоставный вход в пределах бюджета: N предложений = N чанков,
        // разделители — границы исходника.
        val plan = planChunks("Hello there. Fine thanks. Ok.", ::wordTokens, budget = 100)
        assertEquals(listOf("Hello there.", "Fine thanks.", "Ok."), plan.chunks)
        assertEquals(listOf(" ", " "), plan.separators)
    }

    @Test
    fun `sentences are not merged even when joined they fit budget`() {
        // Бюджет 6 токенов = 3 слова: соседние по 2 слова поместились бы
        // склеенными, но упаковка удалена — каждый сегмент отдельный чанк.
        val plan = planChunks("Hello there. Fine thanks. Ok.", ::wordTokens, budget = 6)
        assertEquals(listOf("Hello there.", "Fine thanks.", "Ok."), plan.chunks)
        assertEquals(listOf(" ", " "), plan.separators)
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
    fun `single overbudget segment is cut like any other`() {
        // Ревью #79: прежний ранний возврат «один сегмент — как есть» оставлял
        // сверхбюджетный чанк нетронутым; теперь одиночный сегмент дорезается
        // общим циклом наравне с прочими.
        val plan = planChunks("a long stretch without terminators", ::wordTokens, budget = 2)
        assertEquals(5, plan.chunks.size)
        assertEquals(4, plan.separators.size)
        plan.chunks.forEach { chunk -> assertTrue(wordTokens(chunk) <= 2) }
    }

    @Test
    fun `single segment within budget returned untouched`() {
        val plan = planChunks("a long stretch", ::wordTokens, budget = 8)
        assertEquals(listOf("a long stretch"), plan.chunks)
        assertTrue(plan.separators.isEmpty())
    }

    @Test
    fun `budget exactly equal to joined size still splits per sentence`() {
        // 4 слова × 2 = ровно 8: склеенный вход влезал бы в бюджет, но
        // упаковка удалена — каждое предложение отдельный чанк.
        val plan = planChunks("aa bb. cc dd.", ::wordTokens, budget = 8)
        assertEquals(listOf("aa bb.", "cc dd."), plan.chunks)
        assertEquals(listOf(" "), plan.separators)
    }

    @Test
    fun `blank text yields single untouched chunk`() {
        val plan = planChunks("   ", ::wordTokens, budget = 4)
        assertEquals(listOf("   "), plan.chunks)
        assertTrue(plan.separators.isEmpty())
    }

    // ── Много сегментов: гарантии границ и линейная стоимость (issue #66) ──

    @Test
    fun `many segments stay within budget and reconstruct the source text`() {
        val segments = (1..30).map { "Alpha $it beta gamma." }
        val text = segments.joinToString("\n")
        val plan = planChunks(text, ::wordTokens, budget = 8)
        // Каждый чанк — строго в бюджете по счётчику.
        plan.chunks.forEach { chunk ->
            assertTrue("чанк вне бюджета (${wordTokens(chunk)} токенов): \"$chunk\"", wordTokens(chunk) <= 8)
        }
        assertEquals(segments.size - 1, plan.separators.size)
        // Границы исходника сохранены: склейка чанков через разделители
        // воспроизводит исходный текст.
        val reconstructed = buildString {
            plan.chunks.forEachIndexed { i, chunk ->
                append(chunk)
                if (i < plan.separators.size) append(plan.separators[i])
            }
        }
        assertEquals(text, reconstructed)
    }

    @Test
    fun `length-based counter keeps every chunk within budget over many segments`() {
        // Аддитивный счётчик (длина строки) — проверка точности границ:
        // каждый сегмент остаётся отдельным чанком в пределах бюджета.
        val segments = (1..40).map { "line $it has words." }
        val text = segments.joinToString("\n")
        val plan = planChunks(text, { it.length }, budget = 60)
        plan.chunks.forEach { chunk ->
            assertTrue("чанк вне бюджета (${chunk.length}): \"$chunk\"", chunk.length <= 60)
        }
        val reconstructed = buildString {
            plan.chunks.forEachIndexed { i, chunk ->
                append(chunk)
                if (i < plan.separators.size) append(plan.separators[i])
            }
        }
        assertEquals(text, reconstructed)
        assertEquals("каждое предложение — отдельный чанк", segments.size, plan.chunks.size)
    }

    @Test
    fun `tokenisation cost is linear in segment count, not quadratic`() {
        // Старая реализация пересчитывала tokenCount растущей склейки на
        // каждой итерации — квадратичная стоимость. Регрессия: общая длина
        // строк, поданных в tokenCount, должна расти линейно с числом
        // сегментов (константный запас на единичные точные проверки вблизи
        // бюджета).
        var charsMeasured = 0
        val segments = (1..200).map { "w$it." }
        val text = segments.joinToString(" ")
        val countingTokenCount: (String) -> Int = { s ->
            charsMeasured += s.length
            wordTokens(s)
        }
        planChunks(text, countingTokenCount, budget = 40)
        assertTrue(
            "стоимость токенизации сверхлинейна: замерено $charsMeasured символов, " +
                "длинa текста ${text.length}",
            charsMeasured <= text.length * 12,
        )
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
