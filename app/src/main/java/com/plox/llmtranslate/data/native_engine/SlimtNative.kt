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
     * [IllegalStateException] при нечитаемых/неполных файлах пака, а также
     * если в заголовке .bin не распознаны имена тензоров слоёв
     * (detect_config в slimt_jni.cpp — явная ошибка вместо молчаливого
     * дефолта пресета, ревью #64). Вызывающий код
     * ([com.plox.llmtranslate.domain.runtime.SlimtRuntime.ensureLoaded])
     * перехватывает исключение и мапит его в [com.plox.llmtranslate.domain.model.RuntimeError.LoadFailed].
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

    /**
     * Забрать текст завершённого перевода. null — при некорректном handle
     * (с выброшенным [IllegalStateException]) либо при нехватке памяти в
     * куче JVM: JNI NewString возвращает null без проброса исключения
     * (utf8_to_jstr в slimt_jni.cpp); пустая строка — легитимный пустой
     * результат.
     */
    external fun nativeTakeResult(handle: Long): String?

    /**
     * Забрать текст ошибки последней операции. null — при некорректном
     * handle (с выброшенным [IllegalStateException]) либо при OOM в
     * куче JVM (NewString вернул null без исключения).
     */
    external fun nativeTakeError(handle: Long): String?

    external fun nativeCancel(handle: Long)
}
