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
}
