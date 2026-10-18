package com.plox.llmtranslate.domain.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Тесты паритета токенизатора IndicTrans2 (HF tokenizer.json) с эталонными id.
 *
 * Эталон — app/src/test/resources/indictrans/parity_fixtures.json (ids из
 * Python-библиотеки tokenizers, encode(add_special_tokens=False)).
 * Токенизаторы — РЕАЛЬНЫЕ файлы модели из models/files/INDICTRANS_ONNX/
 * (3.9 и 23.8 МБ — в тестовые ресурсы не кладутся), поэтому при отсутствии
 * модели тест скипается через Assume, а не падает.
 *
 * Язык передаётся префиксом в тексте модели, EOS приложение добавляет руками —
 * поэтому сравниваем ровно TokenizerJson.encode (без спец-токенов) с
 * HF add_special_tokens=False.
 */
class IndicTransTokenizerParityTest {

    companion object {
        private const val MODEL_DIR_REL = "models/files/INDICTRANS_ONNX"

        private val json = Json { ignoreUnknownKeys = true }
        private lateinit var fixtures: JsonObject

        @BeforeClass
        @JvmStatic
        fun setUp() {
            fixtures = json.parseToJsonElement(readResource("/indictrans/parity_fixtures.json")).jsonObject
        }

        private fun readResource(name: String): String =
            IndicTransTokenizerParityTest::class.java.getResourceAsStream(name)
                ?.use { it.readBytes().decodeToString() }
                ?: throw IllegalStateException("Ресурс не найден: $name")

        /**
         * Резолвинг файла модели от рабочего каталога unit-теста:
         * у JUnit-процесса Gradle working dir = app/ — файл выше на уровень;
         * фолбэк на случай запуска из корня репозитория.
         */
        private fun locateModelFile(modelId: String, tokenizer: String): File? {
            val rel = "$MODEL_DIR_REL/$modelId/$tokenizer.json"
            return listOf(File("../$rel"), File(rel)).firstOrNull { it.isFile }
        }

        private fun encodeAndAssertParity(modelId: String, tokenizer: String) {
            val file = locateModelFile(modelId, tokenizer)
            assumeTrue("Модель не найдена ($MODEL_DIR_REL/$modelId) — пропуск", file != null)

            val tok = TokenizerJson()
            tok.load(file!!.absolutePath)
            assertTrue("tokenizer.json не удалось загрузить: ${file.absolutePath}", tok.isLoaded)

            val blocks = fixtures[modelId]!!.jsonObject
            val entries = blocks[tokenizer]!!.jsonArray
            for (el in entries) {
                val obj = el.jsonObject
                val id = obj["id"]!!.jsonPrimitive.content.toInt()
                val source = obj["source"]!!.jsonPrimitive.content
                val expected = obj["input_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }

                val actual = tok.encode(source)
                assertEquals(
                    "encode(\"$source\") [$modelId/$tokenizer, фикстура #$id] не совпал с HF: " +
                        "expected=$expected actual=$actual ${diffHint(expected, actual)}",
                    expected,
                    actual
                )
            }
        }

        /** Позиция первого расхождения (если длины/содержимое различаются). */
        private fun diffHint(expected: List<Int>, actual: List<Int>): String {
            if (expected == actual) return ""
            val pos = (0 until minOf(expected.size, actual.size)).firstOrNull { expected[it] != actual[it] }
                ?: minOf(expected.size, actual.size)
            val e = expected.getOrNull(pos)
            val a = actual.getOrNull(pos)
            return "первое расхождение: позиция=$pos expected=$e actual=$a"
        }
    }

    @Test
    fun `en-indic src encode parity with HF reference`() {
        encodeAndAssertParity("indictrans2-en-indic-int8", "tokenizer_src")
    }

    @Test
    fun `indic-en src encode parity with HF reference`() {
        encodeAndAssertParity("indictrans2-indic-en-int8", "tokenizer_src")
    }

    @Test
    fun `indic-en tgt encode parity with HF reference`() {
        encodeAndAssertParity("indictrans2-indic-en-int8", "tokenizer_tgt")
    }

    @Test
    fun `src tokenizers expose eos id 2`() {
        for (modelId in listOf("indictrans2-en-indic-int8", "indictrans2-indic-en-int8")) {
            val file = locateModelFile(modelId, "tokenizer_src")
            assumeTrue("Модель не найдена ($MODEL_DIR_REL/$modelId) — пропуск", file != null)

            val tok = TokenizerJson()
            tok.load(file!!.absolutePath)
            assertTrue("tokenizer.json не удалось загрузить: ${file.absolutePath}", tok.isLoaded)
            assertEquals("eosId должен быть 2 ($modelId/tokenizer_src)", 2, tok.eosId())
        }
    }
}
