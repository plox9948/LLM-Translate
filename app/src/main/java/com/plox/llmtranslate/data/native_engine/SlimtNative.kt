package com.plox.llmtranslate.data.native_engine

/**
 * JNI-обёртка над slimt (SLIm Marian TRANslator) — C++-движок языковых
 * паков Firefox Translations (Bergamot). Архитектура инференса — как в
 * offline-translator: марian .bin (intgemm alphas) + shortlist lex.bin +
 * SentencePiece vocab, two-pass robust decoder внутри slimt.
 *
     * Поток-контракт: ВСЕ вызовы одного handle (start/poll/take/cancel/destroy)
     * обязаны сериализоваться вызывающим — SlimtRuntime держит однопоточную
     * дисциплину под sessionMutex. Нативная страховка (cookie handle, mutex
     * сессии) — дешёвая эвристика против повторного использования handle
     * (read-after-free UB, см. KDoc nativeDestroy) и сериализацию НЕ
     * заменяет.
 *
 * Библиотека загружается в init объекта: при битом ABI первое обращение к
 * SlimtNative бросает ExceptionInInitializerError, последующие —
 * NoClassDefFoundError (оба — Error, не Exception). Единственная точка
 * входа загрузки [com.plox.llmtranslate.domain.runtime.SlimtRuntime.ensureLoaded]
 * перехватывает их через [com.plox.llmtranslate.domain.runtime.guardModelLoad]
 * (ветки Exception и Error) и мапит в [com.plox.llmtranslate.domain.model.RuntimeError.LoadFailed],
 * процесс не роняет.
 */
object SlimtNative {

    init {
        System.loadLibrary("slimt_jni")
    }

    /**
     * Создать сессию модели. Возвращает нативный handle; при неудаче —
     * ненулевой возврат с РОВНО ОДНИМ выброшенным исключением:
     * [IllegalStateException] при нечитаемых/неполных файлах пака, если в
     * заголовке .bin не распознаны имена тензоров слоёв (detect_config в
     * slimt_jni.cpp — явная ошибка вместо молчаливого дефолта пресета,
     * ревью #64) или при тихом сбое JNI-чтения строк; исходное
     * pending-исключение потока (например OutOfMemoryError из
     * GetStringUTFChars) пробрасывается как есть, без подмены
     * (ревью аудита). Вызывающий код
     * ([com.plox.llmtranslate.domain.runtime.SlimtRuntime.ensureLoaded])
     * перехватывает любой Throwable загрузки и мапит его в
     * [com.plox.llmtranslate.domain.model.RuntimeError.LoadFailed].
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

    /**
     * Уничтожить сессию. После destroy handle невалиден: идемпотентного
     * no-op нет, повторный destroy считается ошибкой контракта.
     * Re-review аудита: cookie-гейт в slimt_jni.cpp (checked_session) —
     * дешёвая эвристика против повторного использования handle: cookie
     * читается из памяти, уже освобождённой delete (формально
     * read-after-free — строгой гарантии нет), полную защиту от
     * use-after-free/double-free даёт только сериализация всех вызовов
     * одного handle одним потоком (поток-контракт выше). Обязанность
     * вызывающего Kotlin-кода
     * ([com.plox.llmtranslate.domain.runtime.SlimtRuntime]) — после
     * УСПЕШНОГО nativeDestroy занулить переменную handle (0L), чтобы
     * поздние экспорты отбивались нулевой веткой проверки без
     * разыменования освобождённой памяти.
     */
    external fun nativeDestroy(handle: Long)

    /** Запустить перевод; повторный вызов до завершения — IllegalStateException. */
    external fun nativeStart(handle: Long, text: String)

    /**
     * Проверить статус: 0 — идёт, 1 — результат готов, 2 — ошибка,
     * 3 — отменено через [nativeCancel]. Код 2 возвращается и для
     * незапущенной или уже снятой (не pending) сессии — на это опирается
     * дренажный цикл SlimtRuntime (drainNativeWorkerUnlocked), поэтому
     * незапущенный дренаж завершается сразу и не висит (ревью #83).
     */
    external fun nativePoll(handle: Long): Int

    /**
     * Забрать текст завершённого перевода; строка ИЗЫМАЕТСЯ из сессии —
     * повторный вызов до следующего [nativeStart] вернёт пустую строку,
     * а не устаревший текст (ревью аудита). null — при некорректном handle
     * (с выброшенным [IllegalStateException]) либо при нехватке памяти в
     * куче JVM: JNI NewString возвращает null без проброса исключения
     * (utf8_to_jstr в slimt_jni.cpp); пустая строка — легитимный пустой
     * результат.
     */
    external fun nativeTakeResult(handle: Long): String?

    /**
     * Забрать текст ошибки последней операции; строка ИЗЫМАЕТСЯ из сессии
     * (повторный вызов — пустая строка, см. [nativeTakeResult]). null — при
     * некорректном handle (с выброшенным [IllegalStateException]) либо при
     * OOM в куче JVM (NewString вернул null без исключения).
     */
    external fun nativeTakeError(handle: Long): String?

    external fun nativeCancel(handle: Long)
}
