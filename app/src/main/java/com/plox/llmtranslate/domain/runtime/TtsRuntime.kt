package com.plox.llmtranslate.domain.runtime

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.plox.llmtranslate.domain.model.RuntimeError
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Рантайм TTS на системном Android TextToSpeech.
 * Fallback на RHVoice/eSpeak NG выполняется через системный TTS:
 * если установлен альтернативный движок — Android сам его использует.
 */
@Singleton
class TtsRuntime @Inject constructor(
    private val context: Context
) {

    private var tts: TextToSpeech? = null
    private var ready = false

    /**
     * Инициализировать системный TTS.
     * @return null при успехе или [RuntimeError].
     */
    suspend fun init(): RuntimeError? = suspendCancellableCoroutine { cont ->
        if (tts != null && ready) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    if (cont.isActive) cont.resume(null)
                } else {
                    Timber.e("TTS инициализация: статус $status")
                    if (cont.isActive) {
                        cont.resume(RuntimeError.LoadFailed("tts", "Системный TTS недоступен"))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка инициализации TTS")
            if (cont.isActive) {
                cont.resume(RuntimeError.LoadFailed("tts", e.message ?: "Неизвестная ошибка"))
            }
        }
    }

    /**
     * Поддерживается ли язык.
     * @param lang код языка (ru, en, ne).
     */
    fun isLanguageAvailable(lang: String): Boolean {
        val engine = tts ?: return false
        return engine.isLanguageAvailable(Locale(lang)) >=
            TextToSpeech.LANG_AVAILABLE
    }

    /**
     * Озвучить текст на языке [lang].
     * @return null при успехе или [RuntimeError].
     */
    suspend fun speak(text: String, lang: String): RuntimeError? {
        val engine = tts
            ?: return RuntimeError.LoadFailed("tts", "TTS не инициализирован")
        if (!ready) return RuntimeError.LoadFailed("tts", "TTS не готов")

        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                Timber.d("TTS завершён: $utteranceId")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Timber.e("TTS ошибка: $utteranceId")
            }
        })

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return if (result == TextToSpeech.ERROR) {
            RuntimeError.InferenceFailed("tts", "Не удалось озвучить текст")
        } else {
            null
        }
    }

    /** Остановить озвучивание. */
    fun stop() {
        tts?.stop()
    }

    /**
     * Разгрузить TTS и освободить память.
     */
    fun release() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
