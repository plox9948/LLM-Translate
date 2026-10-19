package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты хранилища переносимой записи: атомарность consume(expected) —
 * новый запрос, поставленный между чтением и очисткой коллектором VM,
 * не должен теряться (ревью).
 */
class PendingTranslationHolderTest {

    private fun entry(id: Long) = TranslationHistoryEntry(
        id = id,
        sourceLang = "ru",
        targetLang = "en",
        inputText = "text-$id",
        resultText = "result-$id",
        createdAt = id
    )

    @Test
    fun `consume clears only the expected entry`() {
        val holder = PendingTranslationHolder()
        val first = entry(1)
        val second = entry(2)

        holder.request(first)
        // Новое значение пришло раньше, чем коллектор забрал первое.
        holder.request(second)

        assertFalse(
            "consume(first) не должен затирать более новый запрос",
            holder.consume(first)
        )
        assertEquals(second, holder.pending.value)

        assertTrue(holder.consume(second))
        assertNull(holder.pending.value)
    }

    @Test
    fun `consume of processed entry clears it`() {
        val holder = PendingTranslationHolder()
        val first = entry(1)
        holder.request(first)

        assertTrue(holder.consume(first))
        assertNull(holder.pending.value)
        // Повторный consume пустого хранилища — no-op.
        assertFalse(holder.consume(first))
    }

    @Test
    fun `request replaces pending entry and flow re-emits it`() {
        val holder = PendingTranslationHolder()
        holder.request(entry(1))
        assertEquals(1L, holder.pending.value?.id)
        holder.request(entry(2))
        assertEquals(2L, holder.pending.value?.id)
    }
}
