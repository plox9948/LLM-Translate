package com.plox.llmtranslate.domain.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Тесты нормализатора precompiled_charsmap (darts-trie из sentencepiece).
 *
 * Реальный blob `nmt_nfkc.bin` — скомпилированная NFKC-карта sentencepiece;
 * ожидаемые значения взяты из эталонных тестов spm_precompiled (эталонная
 * реализация HF tokenizers). Тестовые строки заданы кодпоинтами, чтобы
 * исключить влияние кодировок исходников.
 */
class CharsmapNormalizerTest {

    private val nfkc by lazy {
        val bytes = requireNotNull(
            javaClass.getResourceAsStream("/charsmap/nmt_nfkc.bin")
        ) { "ресурс /charsmap/nmt_nfkc.bin не найден" }.use { it.readBytes() }
        TokenizerJson.CharsmapNormalizer(bytes)
    }

    // Строка из Unicode-кодпоинтов (ASCII-only источник).
    private fun s(vararg cp: Int): String = StringBuilder().apply { for (code in cp) appendCodePoint(code) }.toString()

    private val ligFi = s(0xFB01)          // лигатура fi
    private val ligSt = s(0xFB05)          // лигатура long-s + t
    private val zwj = 0x200D               // zero-width joiner
    private val zwsp = 0x200B              // zero-width space

    // --- Реальный charsmap (nmt_nfkc.bin) ---

    @Test
    fun `real map normalizes ligature fi`() {
        // Эталон: leaf value=2130 -> запись "fi" в normalized.
        assertEquals("fi", nfkc.normalize(ligFi))
    }

    @Test
    fun `real map normalizes long-s t ligature`() {
        assertEquals("st", nfkc.normalize(ligSt))
    }

    @Test
    fun `real map replaces zero-width chars with space`() {
        assertEquals(" ", nfkc.normalize(s(zwj)))
        assertEquals("a b", nfkc.normalize("a" + zwsp.toChar() + "b"))
    }

    @Test
    fun `real map decomposes devanagari precomposed char`() {
        // Эталон test_precompiled_hindi: U+095C -> U+0921 U+093C.
        val original = s(0x095C, 0x0940, 0x20, 0x0926, 0x0941, 0x0916)
        val expected = s(0x0921, 0x093C, 0x0940, 0x20, 0x0926, 0x0941, 0x0916)
        assertEquals(expected, nfkc.normalize(original))
    }

    @Test
    fun `real map composes multi-char sequence`() {
        // Эталон test_precompiled_multi_char_replace_bug:
        // U+0627 U+0653 -> U+0622 (графема из двух символов).
        val original = s(0x0627, 0x0653)
        val expected = s(0x0622)
        assertEquals(expected, nfkc.normalize(original))
    }

    @Test
    fun `real map expands sara am to nikahit plus sara aa`() {
        // Эталон: U+0E33 -> U+0E4D U+0E32 в середине фразы.
        val prefix = intArrayOf(0x0E40, 0x0E02, 0x0E32, 0x0E44, 0x0E21, 0x0E48, 0x0E44, 0x0E14, 0x0E49, 0x0E1E, 0x0E39, 0x0E14, 0x0E2A, 0x0E31, 0x0E01, 0x0E04)
        val original = s(*(prefix + intArrayOf(0x0E33)))
        val expected = s(*(prefix + intArrayOf(0x0E4D, 0x0E32)))
        assertEquals(expected, nfkc.normalize(original))
    }

    @Test
    fun `real map passes ascii through`() {
        assertEquals("hello", nfkc.normalize("hello"))
        // ZWJ заменяется на пробел (неразложившаяся графема идёт посимвольно).
        assertEquals("hel lo", nfkc.normalize("hel" + zwj.toChar() + "lo"))
    }

    // --- Синтетический мини-blob по формату ---

    /**
     * Mini-blob формата `[u32 trie_size][trie u32 LE][normalized \\0-записи]`.
     * Правила (одна запись = одна графема, поэтому ключи — кодовые точки):
     * "a" (0x61) -> "A"; "e" (0x65) -> "E"; "e"+U+0301 (65 CC 81) -> "Z"
     * (длиннее матча 'e' — проверка выбора наибольшего совпадения);
     * U+0401 (Ё, D0 81) -> U+0451 (ё).
     */
    private fun miniCharsmap(): ByteArray {
        val trie = IntArray(256)
        // Корень: смещение 1 (offset=1 -> unit = 1 shl 10, label/leaf cleared).
        trie[0] = 1 shl 10
        // 'a': idx = 1 xor 0x61 = 0x60; метка+leaf, смещение 1 -> value-юнит 0x61.
        trie[0x60] = 0x61 or 0x100 or (1 shl 10)
        trie[0x61] = 0 // value 0: запись "A".
        // 'e': idx = 1 xor 0x65 = 0x64; метка+leaf, смещение 1 -> value-юнит 0x65.
        trie[0x64] = 0x65 or 0x100 or (1 shl 10)
        trie[0x65] = 2 // value 2: запись "E".
        // Продолжение 'e': CC 81 (U+0301) от узла 0x65: 0x65 xor 0xCC = 0xA9 (без leaf).
        trie[0xA9] = 0xCC or (1 shl 10) // смещение 1 -> следующий узел 0xA8.
        trie[0x29] = 0x81 or 0x100 or (1 shl 10) // 0xA8 xor 0x81 = 0x29; leaf -> value 0x28.
        trie[0x28] = 4 // value 4: запись "Z".
        // Ё = D0 81: idx 1 xor 0xD0 = 0xD1 (метка без leaf); 0xD1 xor 0x81 = 0x50.
        trie[0xD1] = 0xD0
        trie[0x50] = 0x81 or 0x100 or (1 shl 10) // смещение 1 -> value-юнит 0x51.
        trie[0x51] = 6 // value 6: запись "ё".
        val normalized = "A".toByteArray(StandardCharsets.UTF_8) + 0 +
            "E".toByteArray(StandardCharsets.UTF_8) + 0 +
            "Z".toByteArray(StandardCharsets.UTF_8) + 0 +
            s(0x0451).toByteArray(StandardCharsets.UTF_8) + 0
        val buf = ByteBuffer.allocate(4 + trie.size * 4 + normalized.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(trie.size * 4)
        trie.forEach { buf.putInt(it) }
        buf.put(normalized)
        return buf.array()
    }

    private val mini by lazy { TokenizerJson.CharsmapNormalizer(miniCharsmap()) }

    private val capitalYo = s(0x0401)
    private val smallYo = s(0x0451)

    @Test
    fun `mini map normalizes capital yo to small yo`() {
        assertEquals(smallYo, mini.normalize(capitalYo))
        // Обратного правила нет — строчная ё проходит насквозь.
        assertEquals(smallYo, mini.normalize(smallYo))
    }

    @Test
    fun `mini map picks longest match not shortest`() {
        // 'e' -> "E" короче; полный матч графемы 'e'+U+0301 -> "Z" длиннее.
        assertEquals("E", mini.normalize("e"))
        val combined = "e" + 0x0301.toChar()
        assertEquals("Z", mini.normalize(combined))
        assertEquals("A", mini.normalize("a"))
    }

    @Test
    fun `mini map passes unmatched chars through`() {
        assertEquals("q" + smallYo, mini.normalize("q" + capitalYo))
        assertEquals("Z", mini.normalize("Z"))
    }

    // --- Валидность blob ---

    @Test
    fun `rejects blob with corrupt trie size`() {
        val bytes = ByteArray(8)
        // trie_size = 1000 не кратен 1024.
        bytes[0] = 0xE8.toByte()
        bytes[1] = 0x03
        assertThrows(IllegalArgumentException::class.java) {
            TokenizerJson.CharsmapNormalizer(bytes)
        }
    }

    @Test
    fun `rejects empty blob`() {
        assertThrows(IllegalArgumentException::class.java) {
            TokenizerJson.CharsmapNormalizer(ByteArray(4))
        }
    }
}
