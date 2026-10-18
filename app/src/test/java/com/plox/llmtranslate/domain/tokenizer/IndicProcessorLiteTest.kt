package com.plox.llmtranslate.domain.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты препроцессинга IndicProcessorLite (NFC + punc_norm + языковой префикс)
 * и детокенизации postprocess (trivial_detokenize).
 */
class IndicProcessorLiteTest {

    @Test
    fun `preprocess adds language prefix`() {
        assertEquals(
            "npi_Deva eng_Latn नमस्ते",
            IndicProcessorLite.preprocess("नमस्ते", "npi_Deva", "eng_Latn")
        )
    }

    @Test
    fun `preprocess applies nfc normalization`() {
        // NFD: "e" + комбинируемый острый акцент → NFC: "é".
        val nfd = "e\u0301"
        assertEquals("eng_Latn npi_Deva \u00E9", IndicProcessorLite.preprocess(nfd, "eng_Latn", "npi_Deva"))
    }

    @Test
    fun `preprocess collapses multiple spaces`() {
        assertEquals(
            "npi_Deva eng_Latn नमस्ते दुनियामा",
            IndicProcessorLite.preprocess("नमस्ते    दुनियामा", "npi_Deva", "eng_Latn")
        )
    }

    @Test
    fun `empty text returns empty string`() {
        assertEquals("", IndicProcessorLite.preprocess("", "npi_Deva", "eng_Latn"))
    }

    // ---- punc_norm: английская пунктуация ----------------------------------

    @Test
    fun `prefix is invariant under punc norm`() {
        // Префикс вставляется после punc_norm и сам пунктуацией не затрагивается.
        val out = IndicProcessorLite.preprocess("Done.", "eng_Latn", "npi_Deva")
        assertEquals("eng_Latn npi_Deva Done .", out)
    }

    @Test
    fun `english comma is separated`() {
        assertEquals(
            "eng_Latn npi_Deva Hello , world",
            IndicProcessorLite.preprocess("Hello, world", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `english question mark is separated`() {
        assertEquals(
            "eng_Latn npi_Deva What is this ?",
            IndicProcessorLite.preprocess("What is this?", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `english quotes and dashes are normalized and separated`() {
        val input = "\u201CHello\u201D \u2014 world"
        assertEquals(
            "eng_Latn npi_Deva \" Hello \" - world",
            IndicProcessorLite.preprocess(input, "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `apostrophe inside word is not touched`() {
        assertEquals(
            "eng_Latn npi_Deva Don\u2019t stop",
            IndicProcessorLite.preprocess("Don\u2019t stop", "eng_Latn", "npi_Deva")
        )
    }

    // ---- punc_norm: защиты --------------------------------------------------

    @Test
    fun `decimal point is protected`() {
        assertEquals(
            "eng_Latn npi_Deva It is 3.5 today .",
            IndicProcessorLite.preprocess("It is 3.5 today.", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `date dots are protected`() {
        assertEquals(
            "eng_Latn npi_Deva Date 22.10.2026 ok .",
            IndicProcessorLite.preprocess("Date 22.10.2026 ok.", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `time colon is protected`() {
        assertEquals(
            "eng_Latn npi_Deva Meeting at 5:30",
            IndicProcessorLite.preprocess("Meeting at 5:30", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `thousands comma is protected`() {
        assertEquals(
            "eng_Latn npi_Deva 1,000 items",
            IndicProcessorLite.preprocess("1,000 items", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `initials are not broken`() {
        assertEquals(
            "eng_Latn npi_Deva A. S. Pushkin wrote",
            IndicProcessorLite.preprocess("A. S. Pushkin wrote", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `url is not broken inside`() {
        assertEquals(
            "eng_Latn npi_Deva Visit https://example.com/path?q=1 now",
            IndicProcessorLite.preprocess("Visit https://example.com/path?q=1 now", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `email is not broken inside`() {
        assertEquals(
            "eng_Latn npi_Deva Mail user@example.com please",
            IndicProcessorLite.preprocess("Mail user@example.com please", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `domain dot inside sentence is not broken`() {
        assertEquals(
            "eng_Latn npi_Deva example.com works fine .",
            IndicProcessorLite.preprocess("example.com works fine.", "eng_Latn", "npi_Deva")
        )
    }

    @Test
    fun `ellipsis collapsed to single char with spaces`() {
        assertEquals(
            "eng_Latn npi_Deva Wait \u2026 what ?",
            IndicProcessorLite.preprocess("Wait... what?", "eng_Latn", "npi_Deva")
        )
    }

    // ---- punc_norm: непальский исходник -------------------------------------

    @Test
    fun `nepali source keeps punctuation glue`() {
        // Для npi_Deva пробелы вокруг ,/?/। не расставляются.
        assertEquals(
            "npi_Deva eng_Latn नमस्ते, कस्तो?",
            IndicProcessorLite.preprocess("नमस्ते, कस्तो?", "npi_Deva", "eng_Latn")
        )
    }

    @Test
    fun `nepali source quotes normalized only`() {
        assertEquals(
            "npi_Deva eng_Latn \"नमस्ते\"",
            IndicProcessorLite.preprocess("\u201Eनमस्ते\u201C", "npi_Deva", "eng_Latn")
        )
    }

    // ---- postprocess (trivial_detokenize) -----------------------------------

    @Test
    fun `postprocess english removes spaces before punctuation`() {
        assertEquals(
            "Hello. Our flight delayed. Thank you.",
            IndicProcessorLite.postprocess("Hello . Our flight delayed . Thank you .", "eng_Latn")
        )
    }

    @Test
    fun `postprocess english joins parentheses`() {
        assertEquals("(maybe)", IndicProcessorLite.postprocess("( maybe )", "eng_Latn"))
        assertEquals("[note]", IndicProcessorLite.postprocess("[ note ]", "eng_Latn"))
    }

    @Test
    fun `postprocess english joins numeric punctuation`() {
        assertEquals("5:30", IndicProcessorLite.postprocess("5 : 30", "eng_Latn"))
        assertEquals("3.5", IndicProcessorLite.postprocess("3 . 5", "eng_Latn"))
        assertEquals("1,000", IndicProcessorLite.postprocess("1 , 000", "eng_Latn"))
    }

    @Test
    fun `postprocess english joins quotes`() {
        assertEquals(
            "He said \"hi\".",
            IndicProcessorLite.postprocess("He said \" hi \" .", "eng_Latn")
        )
    }

    @Test
    fun `postprocess english removes space before percent`() {
        assertEquals("50% ready", IndicProcessorLite.postprocess("50 % ready", "eng_Latn"))
    }

    @Test
    fun `postprocess english is idempotent`() {
        val once = IndicProcessorLite.postprocess(
            "Hello . ( maybe ) 5 : 30 . He said \" hi \" .", "eng_Latn"
        )
        val twice = IndicProcessorLite.postprocess(once, "eng_Latn")
        assertEquals(once, twice)
    }

    @Test
    fun `postprocess nepali removes space before danda keeps after`() {
        assertEquals(
            "नमस्कार। धन्यवाद।",
            IndicProcessorLite.postprocess("नमस्कार । धन्यवाद ।", "npi_Deva")
        )
    }

    @Test
    fun `postprocess nepali idempotent and stable on glued input`() {
        val once = IndicProcessorLite.postprocess("नमस्कार । धन्यवाद ।", "npi_Deva")
        val twice = IndicProcessorLite.postprocess(once, "npi_Deva")
        assertEquals(once, twice)
        // Вход без лишних пробелов перед пунктуацией не меняется.
        assertEquals("नमस्कार। धन्यवाद।", IndicProcessorLite.postprocess("नमस्कार। धन्यवाद।", "npi_Deva"))
    }
}
