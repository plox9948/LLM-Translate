package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.userMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Проверка направления активного пучка (ревью #79): чужая модель не должна
 * переводить молча; импорт без объявленных языков — проверка пропускается.
 */
class DirectionMismatchTest {

    private fun info(vararg langs: String) = LoadedModelInfo(
        modelId = "m",
        displayName = "M",
        category = ModelCategory.TRANSLATION,
        ramRequiredMb = 10,
        languages = langs.toList()
    )

    @Test
    fun `pair covered by model languages passes`() {
        val model = info("ru", "en")
        assertNull(model.directionMismatch("slimt", "ru", "en"))
        assertNull(model.directionMismatch("slimt", "en", "ru"))
    }

    @Test
    fun `pair outside model languages fails`() {
        val model = info("en", "ne")
        val err = model.directionMismatch("indictrans", "ru", "en")
        assertNotNull(err)
        assertEquals("indictrans", err!!.runtime)
        assertEquals(
            "Модель загружена для другого направления перевода: ru→en (языки модели: en, ne)",
            err.userMessage()
        )
    }

    @Test
    fun `chain hop through hub passes on hub model`() {
        // ne→ru цепочкой: hop ne→en на модели [ne,en].
        assertNull(info("ne", "en").directionMismatch("indictrans", "ne", "en"))
    }

    @Test
    fun `model without declared languages skips check`() {
        assertNull(info().directionMismatch("marian", "ru", "ne"))
    }
}
