package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Юнит-тесты чистой функции [RepetitionTrimmer.trim] — дедупликация
 * подряд идущих повторов многословных блоков в выводе IndicTrans2
 * (пост-обработка вместо штрафов в логитах).
 */
class RepetitionTrimmerTest {

    @Test
    fun `collapses full devanagari phrase duplication from the bug`() {
        // Случай из issue #28: «Welcome» => «स्वागत छ स्वागत छ ।»
        assertEquals(
            "स्वागत छ ।",
            RepetitionTrimmer.trim("स्वागत छ स्वागत छ ।")
        )
    }

    @Test
    fun `collapses triple cyrillic phrase duplication to one`() {
        assertEquals(
            "привет мир",
            RepetitionTrimmer.trim("привет мир привет мир привет мир")
        )
    }

    @Test
    fun `collapses duplicated block in the middle keeping the tail`() {
        assertEquals(
            "я хочу сказать спасибо вам за всё",
            RepetitionTrimmer.trim("я хочу сказать спасибо вам спасибо вам за всё")
        )
    }

    @Test
    fun `collapses full copies but keeps partial third copy tail`() {
        // Третья копия блока неполная — вырезаются только полные дубли.
        assertEquals(
            "one two one three",
            RepetitionTrimmer.trim("one two one two one three")
        )
    }

    @Test
    fun `single word repeats are never touched`() {
        // Легитимная эмфаза и полное дублирование одного слова без
        // обрамляющей пунктуации — не трогается (правило 2 не срабатывает).
        assertEquals("very very good", RepetitionTrimmer.trim("very very good"))
        assertEquals("hi hi", RepetitionTrimmer.trim("hi hi"))
        assertEquals("да да да", RepetitionTrimmer.trim("да да да"))
        assertEquals("да да да да", RepetitionTrimmer.trim("да да да да"))
    }

    @Test
    fun `collapses single word tail duplicate separated by punctuation from issue 30`() {
        // «Hello» => «नमस्कार , नमस्कार ।» (EN->NE, issue #30).
        assertEquals(
            "नमस्कार ।",
            RepetitionTrimmer.trim("नमस्कार , नमस्कार ।")
        )
        // «नमस्कार» => «Hello , Hello .» (NE->EN, issue #30).
        assertEquals(
            "Hello .",
            RepetitionTrimmer.trim("Hello , Hello .")
        )
    }

    @Test
    fun `collapses single word duplicate with attached punctuation and mixed case`() {
        // Пунктуация прилипает к первому вхождению, регистр разный — тот же дубль.
        assertEquals(
            "Привет, .",
            RepetitionTrimmer.trim("Привет, привет .")
        )
        assertEquals(
            "hello .",
            RepetitionTrimmer.trim("hello , HELLO .")
        )
        // Замыкающая пунктуация второго вхождения переносится отдельным токеном.
        assertEquals(
            "Hello .",
            RepetitionTrimmer.trim("Hello , Hello.")
        )
    }

    @Test
    fun `single word duplicate without punctuation is emphasis and kept`() {
        // Критерий: без единого знака препинания между/после — не трогаем.
        assertEquals("да да", RepetitionTrimmer.trim("да да"))
        assertEquals(
            "X X X",
            RepetitionTrimmer.trim("X X X")
        )
        assertEquals("thank you very much", RepetitionTrimmer.trim("thank you very much"))
    }

    @Test
    fun `does not touch repeats inside words`() {
        // Операции на уровне слов: «частичные» повторы внутри слова целы.
        assertEquals(
            "welcome welcomeback home homeback",
            RepetitionTrimmer.trim("welcome welcomeback home homeback")
        )
    }

    @Test
    fun `non-contiguous repeat is not touched`() {
        // Блок повторяется не подряд — это нормальный текст.
        assertEquals(
            "one two three one two four",
            RepetitionTrimmer.trim("one two three one two four")
        )
        assertEquals(
            "welcome to welcome home",
            RepetitionTrimmer.trim("welcome to welcome home")
        )
    }

    @Test
    fun `punctuation attached to words must match exactly`() {
        // «hello world» и «hello world.» — разные слова, повтор не находится.
        assertEquals(
            "hello world hello world.",
            RepetitionTrimmer.trim("hello world hello world.")
        )
        assertEquals(
            "hello world!",
            RepetitionTrimmer.trim("hello world! hello world!")
        )
    }

    @Test
    fun `empty and blank inputs returned as is`() {
        assertEquals("", RepetitionTrimmer.trim(""))
        assertEquals("   ", RepetitionTrimmer.trim("   "))
        assertEquals("слово", RepetitionTrimmer.trim("слово"))
    }

    @Test
    fun `trim is idempotent`() {
        val once = RepetitionTrimmer.trim("स्वागत छ स्वागत छ ।")
        assertEquals(once, RepetitionTrimmer.trim(once))
        val onceRu = RepetitionTrimmer.trim("спасибо вам спасибо вам спасибо вам друг")
        assertEquals("спасибо вам друг", onceRu)
        assertEquals(onceRu, RepetitionTrimmer.trim(onceRu))
        // Правило 2 тоже идемпотентно: хвост «.» остаётся, дубля больше нет.
        val onceEn = RepetitionTrimmer.trim("नमस्कार , नमस्कार ।")
        assertEquals(onceEn, RepetitionTrimmer.trim(onceEn))
        // Цепной двойной дубль схлопывается итеративно до одного вхождения.
        assertEquals(
            "Hello .",
            RepetitionTrimmer.trim("Hello , Hello . Hello , Hello .")
        )
    }

    @Test
    fun `text without repeats returned verbatim with original spacing`() {
        val text = "обычный   перевод\tбез повторов"
        assertEquals(text, RepetitionTrimmer.trim(text))
    }

    // ── input-aware разрешение повторов (issue #91) ──

    @Test
    fun `legitimate repeats within source multiplicity are kept`() {
        // Тройной повтор блока при разрешении 3 (множественность входа) — цел.
        assertEquals(
            "привет мир привет мир привет мир",
            RepetitionTrimmer.trim("привет мир привет мир привет мир", allowedRepeats = 3)
        )
        // Легитимная многословная эмфаза «thank you thank you»: вход её
        // содержит подряд дважды — вывод с разрешением 2 не схлопывается.
        val echo = "thank you thank you"
        assertEquals(
            echo,
            RepetitionTrimmer.trim(echo, RepetitionTrimmer.maxSourceRepeats(echo))
        )
    }

    @Test
    fun `decoder cycle beyond allowance still collapses`() {
        // Цикловой повтор внутри чанка: выход за разрешение сжимается —
        // но до разрешения, а не до одного вхождения.
        assertEquals(
            "привет мир привет мир",
            RepetitionTrimmer.trim(
                "привет мир привет мир привет мир привет мир привет мир",
                allowedRepeats = 2
            )
        )
        // При разрешении 1 — прежнее схлопывание к одному вхождению.
        assertEquals(
            "one two",
            RepetitionTrimmer.trim("one two one two one two", allowedRepeats = 1)
        )
        // Идемпотентность и при разрешении > 1.
        val cycle6 = "one two one two one two one two one two one two"
        val once = RepetitionTrimmer.trim(cycle6, allowedRepeats = 3)
        assertEquals("one two one two one two", once)
        assertEquals(once, RepetitionTrimmer.trim(once, allowedRepeats = 3))
    }

    @Test
    fun `tail single double kept when repeats are allowed`() {
        // Вход «Hello.» повторён дважды — два вхождения в выводе легитимны,
        // правило 2 (ровно два вхождения) при разрешении >= 2 не применяется.
        assertEquals(
            "Hello , Hello .",
            RepetitionTrimmer.trim("Hello , Hello .", allowedRepeats = 2)
        )
        // Без разрешения — схлопывается как прежде (ожидания не ослаблены).
        assertEquals("Hello .", RepetitionTrimmer.trim("Hello , Hello ."))
    }

    @Test
    fun `maxSourceRepeats counts consecutive block repeats of the input`() {
        assertEquals(1, RepetitionTrimmer.maxSourceRepeats(""))
        assertEquals(1, RepetitionTrimmer.maxSourceRepeats("   "))
        assertEquals(1, RepetitionTrimmer.maxSourceRepeats("Hello there."))
        // Блок повторяется не подряд — множественности нет.
        assertEquals(1, RepetitionTrimmer.maxSourceRepeats("one two three one two four"))
        // Предложения абзаца, повторённые ×3 подряд (сценарий #91: склейка
        // переводов предложных чанков легитимно повторяющегося абзаца).
        assertEquals(
            3,
            RepetitionTrimmer.maxSourceRepeats("base matram. arko vakya. base matram. arko vakya. base matram. arko vakya.")
        )
        assertEquals(3, RepetitionTrimmer.maxSourceRepeats("मलाई थाहा छ। मलाई थाहा छ। मलाई थाहा छ।"))
        // Однословный повтор подряд тоже считается.
        assertEquals(4, RepetitionTrimmer.maxSourceRepeats("no no no no"))
        // Пунктуация и регистр при сравнении не учитываются.
        assertEquals(2, RepetitionTrimmer.maxSourceRepeats("Hello! hello."))
    }

    @Test
    fun `maxSourceRepeats treats punctuation boundaries as token separators on dense joins`() {
        // Регрессия девайс-верификации #91: longNepaliParagraphChunking — вход
        // склеен дандой БЕЗ пробела (base।base), на стыках копий последнее
        // слово одной копии сливалось с дандой/первым словом следующей в
        // составной токен и терминальная копия не совпадала (2 вместо 3).
        assertEquals(
            3,
            RepetitionTrimmer.maxSourceRepeats("मलाई थाहा छ।मलाई थाहा छ।मलाई थाहा छ।")
        )
        // Dense-склейка । и склейка с пробелом дают одинаковый результат.
        val spaced = "base matram. arko vakya. base matram. arko vakya. base matram. arko vakya."
        val denseDanda = listOf(
            "base matram", "arko vakya", "base matram", "arko vakya",
            "base matram", "arko vakya",
        ).joinToString("।") + "।"
        assertEquals(3, RepetitionTrimmer.maxSourceRepeats(denseDanda))
        assertEquals(
            RepetitionTrimmer.maxSourceRepeats(spaced),
            RepetitionTrimmer.maxSourceRepeats(denseDanda),
        )
        // Dense-склейка точкой без пробела — аналогично.
        val denseDot = listOf(
            "base matram", "arko vakya", "base matram", "arko vakya",
            "base matram", "arko vakya",
        ).joinToString(".") + "."
        assertEquals(3, RepetitionTrimmer.maxSourceRepeats(denseDot))
    }

    @Test
    fun `joined chunk outputs of a paragraph repeated three times survive source-aware trim`() {
        // Регрессия #91: вход = одна база ×3, чанкинг «1 предложение = 1 чанк»,
        // переводы чанков идентичны — склейка обязана сохранить все три копии.
        val source = "base matram. arko vakya. base matram. arko vakya. base matram. arko vakya."
        val joined = "Base only. Another sentence. Base only. Another sentence. " +
            "Base only. Another sentence."
        assertEquals(
            joined,
            RepetitionTrimmer.trim(joined, RepetitionTrimmer.maxSourceRepeats(source))
        )
        // Прежний глобальный трим без знания о входе сжёг бы две копии.
        assertEquals(
            "Base only. Another sentence.",
            RepetitionTrimmer.trim(joined)
        )
    }
}
