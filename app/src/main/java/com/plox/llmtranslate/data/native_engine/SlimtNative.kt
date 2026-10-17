package com.plox.llmtranslate.data.native_engine

/**
 * JNI-обёртка над slimt (SLIm Marian TRANslator) — C++-движок языковых
 * паков Firefox Translations (Bergamot). Архитектура инференса — как в
 * offline-translator: марian .bin (intgemm alphas) + shortlist lex.bin +
 * SentencePiece vocab, two-pass robust decoder внутри slimt.
 */
object SlimtNative {

    init {
        System.loadLibrary("slimt_jni")
    }

    /**
     * Создать сессию модели. Возвращает нативный handle или 0 с выброшенным
     * [NativeLoadException] при нечитаемых/неполных файлах пака.
     *
     * @param targetVocab — trgvocab.*.spm для двух-словарных паков (CJK);
     *        null для одних словарей (ru⇄en).
     */
    external fun nativeCreate(
        modelPath: String,
        vocabPath: String,
        shortlistPath: String,
        targetVocabPath: String?
    ): Long

    external fun nativeDestroy(handle: Long)

    /** Запустить перевод; повторный вызов до завершения — IllegalStateException. */
    external fun nativeStart(handle: Long, text: String)

    /**
     * Проверить статус: 0 — идёт, 1 — результат готов, 2 — ошибка,
     * 3 — отменено через [nativeCancel].
     */
    external fun nativePoll(handle: Long): Int

    external fun nativeTakeResult(handle: Long): String

    external fun nativeTakeError(handle: Long): String

    external fun nativeCancel(handle: Long)
}
