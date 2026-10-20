package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Тесты чтения generation_config.json Marian-модели (ревью аудита):
 * отсутствие, битый JSON или отсутствующие обязательные поля — null
 * (ensureLoaded фиксирует явный LoadFailed), а НЕ прежние молчаливые
 * дефолты start=0/eos=0, дававшие «Пустой перевод» вместо ошибки загрузки.
 */
class MarianGenerationConfigTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val runtime = MarianOnnxRuntime(FakeModelRetentionProvider(keep = true))

    @Test
    fun `missing file yields null instead of silent defaults`() {
        val missing = File(folder.root, "generation_config.json")
        assertNull(runtime.parseGenerationConfig(missing))
    }

    @Test
    fun `broken json yields null`() {
        val file = File(folder.root, "generation_config.json")
        file.writeText("{ это не json")
        assertNull(runtime.parseGenerationConfig(file))
    }

    @Test
    fun `json without required fields yields null`() {
        // Молчаливый старт 0 вместо реального decoder_start_token_id opus-mt
        // — тот же битый декод, что и при отсутствующем файле.
        val file = File(folder.root, "generation_config.json")
        file.writeText("""{"bad_words_ids": [[1]]}""")
        assertNull(runtime.parseGenerationConfig(file))

        val onlyStart = File(folder.root, "gc2.json")
        onlyStart.writeText("""{"decoder_start_token_id": 65000}""")
        assertNull(runtime.parseGenerationConfig(onlyStart))
    }

    @Test
    fun `valid config parses params and optional bad words`() {
        val file = File(folder.root, "generation_config.json")
        file.writeText(
            """
            {
              "decoder_start_token_id": 65000,
              "eos_token_id": 0,
              "bad_words_ids": [[1, 2], [3]]
            }
            """.trimIndent()
        )
        val gen = runtime.parseGenerationConfig(file)
        assertNotNull(gen)
        assertEquals(65000, gen!!.decoderStartTokenId)
        assertEquals(0, gen.eosTokenId)
        assertEquals(setOf(1, 2, 3), gen.badTokenIds)
    }

    @Test
    fun `valid config without bad words is accepted`() {
        val file = File(folder.root, "generation_config.json")
        file.writeText("""{"decoder_start_token_id": 2, "eos_token_id": 3}""")
        val gen = runtime.parseGenerationConfig(file)
        assertNotNull(gen)
        assertEquals(2, gen!!.decoderStartTokenId)
        assertEquals(3, gen.eosTokenId)
        assertEquals(emptySet<Int>(), gen.badTokenIds)
    }
}
