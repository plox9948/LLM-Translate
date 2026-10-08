package com.plox.llmtranslate.domain.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Тесты парсера HF tokenizer.json (BPE / Unigram / added_tokens / decode).
 */
class TokenizerJsonTest {

    // BPE: merges работают на отдельных символах, поэтому пары указываем
    // как соседние символы, а не как составные юниты.
    private val bpeJson = """
        {
          "model": {
            "type": "BPE",
            "vocab": {
              "▁": 0,
              "he": 1,
              "llo": 2,
              "</s>": 4,
              "<unk>": 5
            },
            "merges": [
              ["h", "e"],
              ["▁", "h"],
              ["l", "o"],
              ["l", "lo"]
            ]
          },
          "added_tokens": [
            {"id": 4, "content": "</s>", "special": true},
            {"id": 5, "content": "<unk>", "special": true},
            {"id": 6, "content": "<|ru|>", "special": true}
          ],
          "pre_tokenizer": {"type": "Metaspace", "replacement": "▁", "prepend_scheme": "always"},
          "decoder": {"type": "Metaspace", "replacement": "▁"}
        }
    """

    // Unigram: id = позиция токена в vocab-списке.
    private val unigramJson = """
        {
          "model": {
            "type": "Unigram",
            "vocab": [
              ["▁", 0.1],
              ["he", 0.2],
              ["llo", 0.3],
              ["</s>", -10.0],
              ["<unk>", -20.0]
            ]
          },
          "added_tokens": [
            {"id": 3, "content": "</s>", "special": true},
            {"id": 4, "content": "<unk>", "special": true}
          ],
          "pre_tokenizer": {"type": "Metaspace", "replacement": "▁", "prepend_scheme": "always"}
        }
    """

    @Test
    fun `bpe encode decode round trip`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        val ids = tokenizer.encode("hello")
        assertEquals(listOf(0, 1, 2), ids)
        assertEquals("hello", tokenizer.decode(ids))
    }

    @Test
    fun `bpe specialTokenId resolves eos and unk`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        assertEquals(4, tokenizer.specialTokenId("</s>"))
        assertEquals(5, tokenizer.specialTokenId("<unk>"))
    }

    @Test
    fun `unigram encode returns vocab ids`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(unigramJson))
        val ids = tokenizer.encode("hello")
        assertEquals(listOf(0, 1, 2), ids)
    }

    @Test
    fun `added token language code resolves by id`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        assertEquals(6, tokenizer.specialTokenId("<|ru|>"))
        assertEquals(listOf(6), tokenizer.encode("<|ru|>"))
    }

    @Test
    fun `decode skips special tokens`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        // id 4 = "</s>" (special) — должен быть пропущен.
        assertEquals("hello", tokenizer.decode(listOf(0, 1, 2, 4)))
    }

    private fun writeTempTokenizer(content: String): String {
        val file = Files.createTempFile("tokenizer", ".json")
        file.toFile().deleteOnExit()
        Files.write(file, content.toByteArray(StandardCharsets.UTF_8))
        return file.toFile().absolutePath
    }
}
