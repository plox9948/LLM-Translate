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
    private val llmRuntime: LlmRuntime,
    private val onnxLlmRuntime: OnnxLlmRuntime,
    private val sttRuntime: SttRuntime,
    private val voskSttRuntime: VoskSttRuntime
) {

    /**
     * Движок перевода для типа модели.
     */
    fun translationEngine(runtime: RuntimeType): TranslationEngine = when (runtime) {
        RuntimeType.GGUF -> llmRuntime
        RuntimeType.ONNX -> onnxLlmRuntime
        else -> llmRuntime // fallback
    }

    /**
     * Движок распознавания речи для типа модели.
     */
    fun sttEngine(runtime: RuntimeType): SttEngine = when (runtime) {
        RuntimeType.GGUF -> sttRuntime
        RuntimeType.VOSK -> voskSttRuntime
        else -> sttRuntime // fallback
    }

    /**
     * Разгрузить все рантаймы (при ON_STOP).
     */
    fun releaseAll() {
        llmRuntime.release()
        onnxLlmRuntime.release()
        sttRuntime.release()
        voskSttRuntime.release()
    }
}
