package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.RuntimeType
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
    private val sherpaSttEngine: SherpaSttEngine
) {

    /**
     * Движок перевода для типа модели.
     * Marian — по умолчанию (fallback), IndicTrans — для ne.
     */
    fun translationEngine(runtime: RuntimeType): TranslationEngine = when (runtime) {
        RuntimeType.MARIAN_ONNX -> marianOnnxRuntime
        RuntimeType.INDICTRANS_ONNX -> indicTransOnnxRuntime
        else -> marianOnnxRuntime // fallback
    }

    /**
     * Движок распознавания речи: sherpa-onnx — единственный STT-рантайм.
     */
    fun sttEngine(runtime: RuntimeType): SttEngine = sherpaSttEngine

    /**
     * Разгрузить все рантаймы (при ON_STOP).
     */
    fun releaseAll() {
        marianOnnxRuntime.release()
        indicTransOnnxRuntime.release()
        sherpaSttEngine.release()
    }
}
