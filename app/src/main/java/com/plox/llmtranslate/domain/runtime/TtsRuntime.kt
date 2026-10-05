package com.plox.llmtranslate.domain.runtime

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.plox.llmtranslate.domain.model.RuntimeError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Рантайм TTS на Android TextToSpeech с выбором движка.
 * Поддерживаются значения движка: "system" (движок по умолчанию),
 * "rhvoice" (RHVoice), "espeak" (eSpeak NG). При недоступности
 * выбранного движка выполняется откат на системный по умолчанию.
 */
@Singleton
class TtsRuntime @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastEngine: String? = null

    /**
     * Инициализировать TTS указанным движком.
     * Повторный вызов с тем же движком при готовности — no-op.
     * @param engine "system" | "rhvoice" | "espeak" (настройка ttsEngine).
     * @return null при успехе или [RuntimeError].
     */
    suspend fun init(engine: String): RuntimeError? {
        if (engine == lastEngine && tts != null && ready) return null
        // Смена движка (или повтор после сбоя) — освобождаем старый экземпляр.
        release()
        lastEngine = engine
        val enginePackage = resolveEnginePackage(engine)
        return suspendCancellableCoroutine { cont ->
            try {
                val listener = TextToSpeech.OnInitListener { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ready = true
                        if (cont.isActive) cont.resume(null)
                    } else {
                        Timber.e("TTS инициализация: статус $status")
                        if (cont.isActive) {
                            cont.resume(
                                RuntimeError.LoadFailed("tts", "Системный TTS недоступен")
                            )
                        }
                    }
                }
                tts = if (enginePackage != null) {
                    TextToSpeech(context.applicationContext, listener, enginePackage)
                } else {
                    TextToSpeech(context.applicationContext, listener)
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка инициализации TTS")
                if (cont.isActive) {
                    cont.resume(RuntimeError.LoadFailed("tts", e.message ?: "Неизвестная ошибка"))
                }
            }
        }
    }

    /**
     * Найти пакет движка по настройке. Для "system" или при отсутствии
     * движка (Android < 11, не установлен) возвращает null — движок по умолчанию.
     */
    private fun resolveEnginePackage(engine: String): String? {
        if (engine != "rhvoice" && engine != "espeak") return null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Timber.w("Выбор TTS-движка доступен с Android 11+, используем движок по умолчанию")
            return null
        }
        val needle = engine.lowercase() // "rhvoice"/"espeak" матчатся по пакету и метке.
        return try {
            val pm = context.packageManager
            val infos = pm.queryIntentServices(
                android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0
            )
            val match = infos.firstOrNull { ri ->
                val pkg = ri.serviceInfo?.packageName ?: return@firstOrNull false
                val label = ri.loadLabel(pm)?.toString()?.lowercase().orEmpty()
                pkg.lowercase().contains(needle) || label.contains(needle)
            }
            if (match == null) {
                Timber.w("TTS-движок '$engine' не найден, откат на движок по умолчанию")
                null
            } else {
                val pkg = match.serviceInfo?.packageName
                Timber.d("TTS-движок '$engine': пакет $pkg")
                pkg
            }
        } catch (e: Exception) {
            Timber.w(e, "Не удалось получить список TTS-движков, откат на движок по умолчанию")
            null
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

        // Язык озвучиваемого текста должен быть установлен в движке.
        val langResult = engine.setLanguage(Locale(lang))
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return RuntimeError.InferenceFailed("tts", "Язык не поддерживается: $lang")
        }

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
        lastEngine = null
    }
}
