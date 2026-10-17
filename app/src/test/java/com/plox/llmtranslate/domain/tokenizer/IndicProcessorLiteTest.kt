package com.plox.llmtranslate.domain.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты препроцессинга IndicProcessorLite (NFC + языковой префикс).
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
}
