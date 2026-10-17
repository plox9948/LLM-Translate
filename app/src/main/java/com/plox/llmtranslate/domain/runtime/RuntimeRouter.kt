package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Роутер рантаймов: выбирает движок по типу модели.
 * UI и ViewModel работают только через этот роутер.
 */
@Singleton
class RuntimeRouter @Inject constructor(
    private val marianOnnxRuntime: MarianOnnxRuntime,
    private val indicTransOnnxRuntime: IndicTransOnnxRuntime,
    private val slimtRuntime: SlimtRuntime,
    private val sherpaSttEngine: SherpaSttEngine,
    private val piperTtsRuntime: PiperTtsRuntime
) {

    /**
     * Реактивный список моделей, загруженных в ОЗУ прямо сейчас:
     * движки перевода (Marian, IndicTrans) + STT (Sherpa) + TTS (Piper).
     * Пуст, если всё разгружено; обновляется при каждой загрузке/разгрузке
     * движка.
     */
    val loadedModels: Flow<List<LoadedModelInfo>> = LoadedModelsAggregator.merge(
        listOf(
            marianOnnxRuntime.loadedModels,
            indicTransOnnxRuntime.loadedModels,
            slimtRuntime.loadedModels,
            sherpaSttEngine.loadedModels,
            piperTtsRuntime.loadedModels
        )
    )

    /**
     * Движок перевода для типа модели.
     * Marian — по умолчанию (fallback), IndicTrans — для ne,
     * slimt — для паков Firefox Translations (RU⇄EN).
     */
    fun translationEngine(runtime: RuntimeType): TranslationEngine = when (runtime) {
        RuntimeType.MARIAN_ONNX -> marianOnnxRuntime
        RuntimeType.INDICTRANS_ONNX -> indicTransOnnxRuntime
        RuntimeType.MARIAN_SLIMT -> slimtRuntime
        else -> marianOnnxRuntime // fallback
    }

    /**
     * Движок распознавания речи: sherpa-onnx — единственный STT-рантайм.
     */
    fun sttEngine(): SttEngine = sherpaSttEngine

    /**
     * Убрать пучки удалённой модели из кэшей движков её категории (issue #40):
     * после удаления файлов запись в LRU-кэше с тем же id продолжала
     * «показывать» модель загруженной, а повторная загрузка того же id
     * молча использовала старый нативный handle. Под мью텍сом движка
     * дожидается окончания активного инференса; модели вне движков
     * (системный TTS) — no-op.
     */
    suspend fun releaseModel(model: InstalledModel) {
        when (model.category) {
            ModelCategory.TRANSLATION ->
                translationEngine(model.runtime).releaseModel(model.id)
            ModelCategory.STT -> sherpaSttEngine.releaseModel(model.id)
            ModelCategory.TTS -> Unit
        }
    }

    /**
     * Разгрузить все рантаймы (при ON_STOP). Каждый движок под мьютексом
     * ждёт окончания активного инференса — это нормально для onStop-разгрузки,
     * но вызывать нужно вне главного потока. Последовательный вызов release()
     * разных движков безопасен: их мьютексы независимы, deadlock невозможен.
     */
    suspend fun releaseAll() {
        marianOnnxRuntime.release()
        indicTransOnnxRuntime.release()
        slimtRuntime.release()
        sherpaSttEngine.release()
        // Piper release() не suspend: останавливает озвучку и асинхронно
        // освобождает нативный OfflineTts под своим мьютексом (issue #52).
        piperTtsRuntime.release()
    }
}
