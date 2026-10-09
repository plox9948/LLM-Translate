package com.plox.llmtranslate.domain.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Тесты паритета токенизатора Marian (HF tokenizer.json) с эталонными id.
 *
 * Эталон — app/src/test/resources/marian/reference.json (HF-совместимые ids).
 * Токенизаторы — реальные HF tokenizer.json для пар rus-eng и eng-rus.
 *
 * Ресурсы доступны только через classpath, а TokenizerJson.load требует
 * файловый путь — поэтому копируем ресурс во временный файл.
 */
class MarianTokenizerParityTest {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private lateinit var reference: JsonObject
        private val tokenizers = HashMap<String, TokenizerJson>()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            reference = json.parseToJsonElement(readResource("/marian/reference.json")).jsonObject
            for (pair in listOf("rus-eng", "eng-rus")) {
                // Файлы фикстур используют подчёркивание: tokenizer_rus_eng.json.
                val tmpFile = copyResourceToTempFile("/marian/tokenizer_${pair.replace('-', '_')}.json")
                val tok = TokenizerJson()
                tok.load(tmpFile.absolutePath)
                tokenizers[pair] = tok
            }
        }

        private fun readResource(name: String): String =
            MarianTokenizerParityTest::class.java.getResourceAsStream(name)
                ?.use { it.readBytes().decodeToString() }
                ?: throw IllegalStateException("Ресурс не найден: $name")

        private fun copyResourceToTempFile(name: String): File {
            val stream = MarianTokenizerParityTest::class.java.getResourceAsStream(name)
                ?: throw IllegalStateException("Ресурс не найден: $name")
            val tmp = File.createTempFile("marian_tokenizer", ".json")
            tmp.deleteOnExit()
            tmp.outputStream().use { out -> stream.use { it.copyTo(out) } }
            return tmp
        }
    }

    @Test
    fun `encode parity with HF reference`() {
        val encodes = reference["encode"]!!.jsonArray
        for (el in encodes) {
            val obj = el.jsonObject
            val pair = obj["pair"]!!.jsonPrimitive.content
            val text = obj["text"]!!.jsonPrimitive.content
            val ids = obj["ids"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
            val tok = tokenizers.getValue(pair)

            // EOS должен совпадать с эталоном (id 0).
            val eos = tok.eosId()
            assertNotNull("eosId не определён для пары $pair", eos)
            assertEquals("eosId должен быть 0 (пара $pair)", 0, eos)

            // encode НЕ добавляет EOS — добавляем вручную и сравниваем с эталоном.
            val actual = tok.encode(text) + listOf(eos!!)
            assertEquals(
                "encode(\"$text\") + EOS не совпадает с HF-эталоном (пара $pair)",
                ids,
                actual
            )
        }
    }

    @Test
    fun `decode parity with HF reference`() {
        val decodes = reference["decode"]!!.jsonArray
        for (el in decodes) {
            val obj = el.jsonObject
            val pair = obj["pair"]!!.jsonPrimitive.content
            val ids = obj["ids"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
            val text = obj["text"]!!.jsonPrimitive.content
            val tok = tokenizers.getValue(pair)

            assertEquals(
                "decode($ids) не совпадает с HF-эталонным текстом (пара $pair)",
                text,
                tok.decode(ids)
            )
        }
    }
}
