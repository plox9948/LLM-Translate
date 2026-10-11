package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.RuntimeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты агрегации состояний движков: список моделей, загруженных в ОЗУ.
 */
class LoadedModelsAggregatorTest {

    /** Фейковый движок перевода: состояние пишется при ensureLoaded/release. */
    private class FakeTranslationEngine : TranslationEngine {
        private val state = MutableStateFlow<LoadedModelInfo?>(null)
        override val loadedModel: StateFlow<LoadedModelInfo?> = state

        override suspend fun ensureLoaded(model: InstalledModel): RuntimeError? {
            state.value = LoadedModelInfo(
                modelId = model.id,
                displayName = model.displayName,
                category = ModelCategory.TRANSLATION,
                ramRequiredMb = model.ramRequiredMb
            )
            return null
        }

        override suspend fun translate(text: String, from: String, to: String) =
            Result.success("")

        override suspend fun release() {
            state.value = null
        }
    }

    /** Фейковый STT-движок. */
    private class FakeSttEngine : SttEngine {
        private val state = MutableStateFlow<LoadedModelInfo?>(null)
        override val loadedModel: StateFlow<LoadedModelInfo?> = state

        override suspend fun ensureLoaded(model: InstalledModel): RuntimeError? {
            state.value = LoadedModelInfo(
                modelId = model.id,
                displayName = model.displayName,
                category = ModelCategory.STT,
                ramRequiredMb = model.ramRequiredMb
            )
            return null
        }

        override suspend fun transcribe(wavPath: String, language: String?) =
            Result.success("")

        override suspend fun release() {
            state.value = null
        }
    }

    private val marian = FakeTranslationEngine()
    private val indicTrans = FakeTranslationEngine()
    private val stt = FakeSttEngine()

    /** Тот же порядок движков, что в RuntimeRouter.loadedModels. */
    private fun routerFlow(): Flow<List<LoadedModelInfo>> =
        LoadedModelsAggregator.merge(
            listOf(marian.loadedModel, indicTrans.loadedModel, stt.loadedModel)
        )

    private suspend fun snapshot(): List<LoadedModelInfo> = routerFlow().first()

    private fun translation(id: String, name: String, ram: Int) = model(
        id, name, ModelCategory.TRANSLATION, RuntimeType.MARIAN_ONNX, ram
    )

    private fun sttModel(id: String, name: String, ram: Int) = model(
        id, name, ModelCategory.STT, RuntimeType.SHERPA, ram
    )

    private fun model(
        id: String,
        displayName: String,
        category: ModelCategory,
        runtime: RuntimeType,
        ramRequiredMb: Int
    ) = InstalledModel(
        id = id,
        category = category,
        displayName = displayName,
        description = "",
        runtime = runtime,
        sizeBytes = 0,
        ramRequiredMb = ramRequiredMb,
        languages = listOf("ru", "en"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = "/models/$id",
        bytesDone = 0,
        isBuiltin = true
    )

    @Test
    fun `empty list when nothing loaded`() = runTest {
        assertEquals(emptyList<LoadedModelInfo>(), snapshot())
    }

    @Test
    fun `translation model appears after load with ram estimate`() = runTest {
        marian.ensureLoaded(translation("opus-ru-en", "Opus-MT Tiny (RU→EN)", 480))

        assertEquals(
            listOf(
                LoadedModelInfo("opus-ru-en", "Opus-MT Tiny (RU→EN)", ModelCategory.TRANSLATION, 480)
            ),
            snapshot()
        )
    }

    @Test
    fun `both translation engines can be loaded at once`() = runTest {
        marian.ensureLoaded(translation("opus-ru-en", "Opus-MT Tiny (RU→EN)", 480))
        indicTrans.ensureLoaded(translation("indictrans-en-ne", "IndicTrans2 (EN→Ne)", 650))

        assertEquals(
            listOf(
                LoadedModelInfo("opus-ru-en", "Opus-MT Tiny (RU→EN)", ModelCategory.TRANSLATION, 480),
                LoadedModelInfo("indictrans-en-ne", "IndicTrans2 (EN→Ne)", ModelCategory.TRANSLATION, 650)
            ),
            snapshot()
        )
    }

    @Test
    fun `translation first then stt in engine order`() = runTest {
        stt.ensureLoaded(sttModel("zipformer-ru", "Zipformer (RU)", 310))
        marian.ensureLoaded(translation("opus-ru-en", "Opus-MT Tiny (RU→EN)", 480))

        assertEquals(
            listOf(
                LoadedModelInfo("opus-ru-en", "Opus-MT Tiny (RU→EN)", ModelCategory.TRANSLATION, 480),
                LoadedModelInfo("zipformer-ru", "Zipformer (RU)", ModelCategory.STT, 310)
            ),
            snapshot()
        )
    }

    @Test
    fun `released engine disappears from list`() = runTest {
        marian.ensureLoaded(translation("opus-ru-en", "Opus-MT Tiny (RU→EN)", 480))
        stt.ensureLoaded(sttModel("zipformer-ru", "Zipformer (RU)", 310))

        stt.release()

        assertEquals(
            listOf(
                LoadedModelInfo("opus-ru-en", "Opus-MT Tiny (RU→EN)", ModelCategory.TRANSLATION, 480)
            ),
            snapshot()
        )

        marian.release()
        assertEquals(emptyList<LoadedModelInfo>(), snapshot())
    }

    @Test
    fun `flow re-emits snapshot when engine state changes`() = runTest {
        val marianState = MutableStateFlow<LoadedModelInfo?>(null)
        val sttState = MutableStateFlow<LoadedModelInfo?>(null)
        val snapshots = mutableListOf<List<LoadedModelInfo>>()

        // Живая подписка: каждый emit любого движка даёт новый снапшот потока.
        val collector = launch {
            LoadedModelsAggregator.merge(listOf(marianState, sttState)).toList(snapshots)
        }
        runCurrent()
        assertEquals(listOf(emptyList<LoadedModelInfo>()), snapshots)

        val opus = LoadedModelInfo(
            "opus-ru-en", "Opus-MT Tiny (RU→EN)", ModelCategory.TRANSLATION, 480
        )
        marianState.value = opus
        runCurrent()
        assertEquals(2, snapshots.size)
        assertEquals(listOf(opus), snapshots.last())

        val zipformer = LoadedModelInfo(
            "zipformer-ru", "Zipformer (RU)", ModelCategory.STT, 310
        )
        sttState.value = zipformer
        runCurrent()
        assertEquals(listOf(opus, zipformer), snapshots.last())

        marianState.value = null
        runCurrent()
        assertEquals(listOf(zipformer), snapshots.last())
        assertEquals(4, snapshots.size)

        collector.cancel()
    }
}
