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
    fun `bpe added tokens resolve eos id and unk replaces out-of-vocab`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        // eosId — из added_tokens (движки дополняют вход EOS).
        assertEquals(4, tokenizer.eosId())
        // Вне словаря: "▁" = 0, отсутствующий "x" заменяется на <unk> = 5.
        assertEquals(listOf(0, 5), tokenizer.encode("x"))
    }

    @Test
    fun `unigram encode returns vocab ids`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(unigramJson))
        val ids = tokenizer.encode("hello")
        assertEquals(listOf(0, 1, 2), ids)
    }

    @Test
    fun `added token language code encodes to its id`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        // added-токен языка кодируется целиком в свой id (языковые коды
        // IndicTrans-префикса обрабатываются через addedTokenIdsByContent).
        assertEquals(listOf(6), tokenizer.encode("<|ru|>"))
    }

    @Test
    fun `decode skips special tokens`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        // id 4 = "</s>" (special) — должен быть пропущен.
        assertEquals("hello", tokenizer.decode(listOf(0, 1, 2, 4)))
    }

    @Test
    fun `unsupported pre-tokenizer type fails load`() {
        // Ревью #79: Metaspace — единственная реализованная претокенизация;
        // другой тип — явная ошибка загрузки, а не тихий fallback.
        val json = bpeJson.replace(
            "\"pre_tokenizer\": {\"type\": \"Metaspace\", \"replacement\": \"▁\", \"prepend_scheme\": \"always\"}",
            "\"pre_tokenizer\": {\"type\": \"ByteLevel\"}"
        )
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(json))
        assertEquals(false, tokenizer.isLoaded)
    }

    @Test
    fun `unknown pre-tokenizer type fails load`() {
        val json = unigramJson.replace(
            "\"pre_tokenizer\": {\"type\": \"Metaspace\", \"replacement\": \"▁\", \"prepend_scheme\": \"always\"}",
            "\"pre_tokenizer\": {\"type\": \"Split\", \"pattern\": \"(\\\\p{L}+)\"}"
        )
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(json))
        assertEquals(false, tokenizer.isLoaded)
    }

    @Test
    fun `missing pre-tokenizer section keeps default metaspace`() {
        val json = unigramJson.replace(
            ",\n          \"pre_tokenizer\": {\"type\": \"Metaspace\", \"replacement\": \"▁\", \"prepend_scheme\": \"always\"}",
            ""
        )
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(json))
        assertEquals(true, tokenizer.isLoaded)
        assertEquals(listOf(0, 1, 2), tokenizer.encode("hello"))
    }

    @Test
    fun `bpe segments non-bmp chars as whole code points`() {
        // Символ вне BMP — один юнит (эмодзи = U+1F600), а не пара суррогатов:
        // "▁" = 0, эмодзи отсутствует в vocab → один <unk> = 5 (раньше — два).
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        assertEquals(listOf(0, 5), tokenizer.encode("\uD83D\uDE00"))
    }

    @Test
    fun `unigram keeps non-bmp code point intact when uncovered`() {
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(unigramJson))
        // "▁" покрыт (id 0), code point вне vocab — один пропуск через <unk> = 4.
        assertEquals(listOf(0, 4), tokenizer.encode("\uD83D\uDE00"))
    }

    @Test
    fun `encode without unk skips out-of-vocab token keeping others`() {
        // Без <unk> в vocab/added_tokens неизвестный токен не попадает в
        // последовательность (поведение с предупреждением в лог, ревью).
        val json = """
            {
              "model": {
                "type": "BPE",
                "vocab": {"▁": 0, "he": 1},
                "merges": []
              },
              "pre_tokenizer": {"type": "Metaspace", "replacement": "▁"}
            }
        """
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(json))
        assertEquals(listOf(0), tokenizer.encode("x"))
    }

    @Test
    fun `decode strips leading space but keeps trailing whitespace`() {
        // Эталон HF Metaspace — lstrip: ведущий пробел от префикса снимается,
        // концевые пробелы значимы и сохраняются.
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(bpeJson))
        // 2 = "llo", 0 = "▁" → "llo▁" → "llo ".
        assertEquals("llo ", tokenizer.decode(listOf(2, 0)))
    }

    @Test
    fun `added token without special flag is not special`() {
        // Дефолт special=false (как HF): не-спец added-токен декодируется,
        // а не выкидывается из выхода.
        val json = """
            {
              "model": {
                "type": "BPE",
                "vocab": {"▁": 0, "zz": 6},
                "merges": []
              },
              "added_tokens": [{"id": 6, "content": "zz"}],
              "pre_tokenizer": {"type": "Metaspace", "replacement": "▁"},
              "decoder": {"type": "Metaspace", "replacement": "▁"}
            }
        """
        val tokenizer = TokenizerJson()
        tokenizer.load(writeTempTokenizer(json))
        assertEquals(listOf(6), tokenizer.encode("zz"))
        assertEquals("zz", tokenizer.decode(listOf(6)))
    }

    private fun writeTempTokenizer(content: String): String {
        val file = Files.createTempFile("tokenizer", ".json")
        file.toFile().deleteOnExit()
        Files.write(file, content.toByteArray(StandardCharsets.UTF_8))
        return file.toFile().absolutePath
    }
}
