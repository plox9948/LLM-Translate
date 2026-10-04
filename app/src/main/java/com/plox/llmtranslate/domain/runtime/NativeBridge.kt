package com.plox.llmtranslate.domain.runtime

/**
 * Мост JNI к нативным библиотекам llama.cpp и whisper.cpp.
 * Единственный класс, знающий о нативном коде.
 */
object NativeBridge {

    private const val LLAMA_LIB = "llama_bridge"
    private const val WHISPER_LIB = "whisper_bridge"
    private var llamaLoaded = false
    private var whisperLoaded = false

    private fun ensureLlamaLoaded() {
        if (!llamaLoaded) {
            System.loadLibrary(LLAMA_LIB)
            llamaLoaded = true
        }
    }

    private fun ensureWhisperLoaded() {
        if (!whisperLoaded) {
            System.loadLibrary(WHISPER_LIB)
            whisperLoaded = true
        }
    }

    // --- llama.cpp ---

    /** Загрузить GGUF-модель. @return 0 — успех, отрицательное — код ошибки. */
    @JvmStatic
    fun llamaLoadModel(path: String, nThreads: Int, nCtx: Int): Int {
        ensureLlamaLoaded()
        return nativeLlamaLoadModel(path, nThreads, nCtx)
    }

    /** Разгрузить модель llama.cpp. */
    @JvmStatic
    fun llamaUnloadModel() {
        ensureLlamaLoaded()
        nativeLlamaUnloadModel()
    }

    /** Загружена ли модель llama.cpp. */
    @JvmStatic
    fun llamaIsLoaded(): Boolean {
        ensureLlamaLoaded()
        return nativeLlamaIsLoaded()
    }

    /** Сгенерировать текст по промпту. @return текст или null при ошибке. */
    @JvmStatic
    fun llamaGenerate(prompt: String, maxTokens: Int): String? {
        ensureLlamaLoaded()
        return nativeLlamaGenerate(prompt, maxTokens)
    }

    /** Отменить текущую генерацию. */
    @JvmStatic
    fun llamaCancel() {
        ensureLlamaLoaded()
        nativeLlamaCancel()
    }

    @JvmStatic
    private external fun nativeLlamaLoadModel(path: String, nThreads: Int, nCtx: Int): Int

    @JvmStatic
    private external fun nativeLlamaUnloadModel()

    @JvmStatic
    private external fun nativeLlamaIsLoaded(): Boolean

    @JvmStatic
    private external fun nativeLlamaGenerate(prompt: String, maxTokens: Int): String?

    @JvmStatic
    private external fun nativeLlamaCancel()

    // --- whisper.cpp ---

    /** Загрузить whisper-модель. @return 0 — успех, отрицательное — код ошибки. */
    @JvmStatic
    fun whisperLoadModel(path: String, nThreads: Int): Int {
        ensureWhisperLoaded()
        return nativeWhisperLoadModel(path, nThreads)
    }

    /** Разгрузить whisper-модель. */
    @JvmStatic
    fun whisperUnloadModel() {
        ensureWhisperLoaded()
        nativeWhisperUnloadModel()
    }

    /** Загружена ли whisper-модель. */
    @JvmStatic
    fun whisperIsLoaded(): Boolean {
        ensureWhisperLoaded()
        return nativeWhisperIsLoaded()
    }

    /** Транскрибировать WAV-файл. @return текст или null при ошибке. */
    @JvmStatic
    fun whisperTranscribe(wavPath: String, language: String): String? {
        ensureWhisperLoaded()
        return nativeWhisperTranscribe(wavPath, language)
    }

    @JvmStatic
    private external fun nativeWhisperLoadModel(path: String, nThreads: Int): Int

    @JvmStatic
    private external fun nativeWhisperUnloadModel()

    @JvmStatic
    private external fun nativeWhisperIsLoaded(): Boolean

    @JvmStatic
    private external fun nativeWhisperTranscribe(wavPath: String, language: String): String?
}
