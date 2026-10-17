package com.plox.llmtranslate.domain.runtime

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.TtsEngines
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        /** Таймаут ожидания колбэка инициализации системного TTS. */
        private const val INIT_TIMEOUT_MS = 10_000L
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastEngine: String? = null

    /**
     * Незавершённые озвучивания: utteranceId → колбэк завершения. При старте
     * новой фразы (QUEUE_FLUSH) перехваченная старая фраза не даёт колбэк
     * слушателя, поэтому её pending-колбэк изымается и вызывается сразу —
     * иначе вызывающий экран остаётся с залипшим флагом «озвучиваем» (issue #40).
     */
    private val utterances = TtsUtteranceRegistry()

    /**
     * Сериализует инициализацию: несколько подписчиков настройки ttsEngine
     * (экрана «Перевод» и «Избранное») не запускают параллельные release+init
     * одного синглтона — второй вызов ждёт мьютекс и, видя готовый тот же
     * движок, завершается no-op.
     */
    private val initMutex = Mutex()

    /**
     * Инициализировать TTS указанным движком.
     * Повторный вызов с тем же движком при готовности — no-op.
     * @param engine "system" | "rhvoice" | "espeak" (настройка ttsEngine).
     * @return null при успехе или [RuntimeError].
     */
    suspend fun init(engine: String): RuntimeError? = initMutex.withLock {
        if (engine == lastEngine && tts != null && ready) return@withLock null
        // Смена движка (или повтор после сбоя) — освобождаем старый экземпляр.
        release()
        lastEngine = engine
        val enginePackage = resolveEnginePackage(engine)
        val initError = withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    val listener = TextToSpeech.OnInitListener { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            // Инициализация завершена позже таймаута — готовность
                            // фиксируем, только если продолжение ещё активно.
                            if (cont.isActive) {
                                ready = true
                                cont.resume(null)
                            }
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
                        cont.resume(
                            RuntimeError.LoadFailed("tts", e.message ?: "Неизвестная ошибка")
                        )
                    }
                }
            }
        }
        if (initError != null) return@withLock initError
        if (ready) return@withLock null
        // withTimeoutOrNull вернул null по таймауту (или успешный null без ready —
        // маловероятная гонка): недозагруженный экземпляр выгружаем.
        Timber.e("TTS инициализация: таймаут подключения движка '$engine'")
        release()
        return@withLock RuntimeError.LoadFailed("tts", "Таймаут инициализации TTS")
    }

    /**
     * Найти пакет движка по настройке. Для "system" или при отсутствии
     * движка (Android < 11, не установлен) возвращает null — движок по умолчанию.
     */
    private fun resolveEnginePackage(engine: String): String? {
        if (engine != TtsEngines.RHVOICE && engine != TtsEngines.ESPEAK) return null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Timber.w("Выбор TTS-движка доступен с Android 11+, используем движок по умолчанию")
            return null
        }
        val needle = engine.lowercase() // "rhvoice"/"espeak" матчатся по пакету и метке.
        return try {
            val pm = context.packageManager
            val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val infos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
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
     * Озвучить текст на языке [lang].
     * Колбэк [onDone] приходит с фонового потока UtteranceProgressListener —
     * перебрасывается в главный, как обещает контракт. Состояние «озвучиваем»
     * вызывающий код обязан выставить ДО вызова speak(): короткая фраза может
     * завершиться (onDone) раньше возврата speak(). Прежняя незавершённая фраза
     * перехватывается QUEUE_FLUSH — её onDone вызывается при старте новой
     * (см. [TtsUtteranceRegistry], issue #40).
     * @param onDone колбэк завершения озвучивания (вызывается на главном потоке).
     * @return null при успехе или [RuntimeError].
     */
    suspend fun speak(text: String, lang: String, onDone: (() -> Unit)? = null): RuntimeError? {
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
        // Перехват QUEUE_FLUSH: из реестра изымаются колбэки всех прежних
        // незавершённых фраз и вызываются сейчас — слушатель для перехваченной
        // фразы больше не сработает, а без вызова у прежнего экрана останется
        // залипший флаг «озвучиваем» (issue #40).
        val previous = utterances.start(utteranceId, onDone)
        previous.forEach { callback ->
            scope.launch(Dispatchers.Main) { callback() }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                // Завершение старой (перехваченной/перезаписанной) фразы не должно
                // сбрасывать состояние текущей — реестр отдаёт колбэк только для
                // ещё незавершённой фразы с этим utteranceId.
                val done = utterances.finish(utteranceId) ?: return
                Timber.d("TTS завершён: $utteranceId")
                // Колбэк приходит на фоновом потоке TTS — в главный, по контракту.
                scope.launch(Dispatchers.Main) { done() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Timber.e("TTS ошибка: $utteranceId")
                // Ошибка тоже завершает фразу: колбэк обязателен, иначе у
                // вызывающего экрана останется залипший флаг «озвучиваем».
                val done = utterances.finish(utteranceId) ?: return
                scope.launch(Dispatchers.Main) { done() }
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
        // После shutdown колбэки незавершённых озвучиваний не придут — изымаем
        // их из реестра атомарно и вызываем на главном (тот же паттерн, что
        // drain в speak()), иначе у вызывающего экрана залипается флаг
        // «озвучиваем» при смене TTS-движка во время озвучки (issue #40).
        // Порядок относительно shutdown не важен: однократность гарантирует
        // атомарный изъём из реестра (как в finish()).
        val pending = utterances.drain()
        pending.forEach { callback ->
            scope.launch(Dispatchers.Main) { callback() }
        }
    }
}

/**
 * Реестр незавершённых озвучиваний TTS: utteranceId → колбэк завершения.
 *
 * Проблема (issue #40): при QUEUE_FLUSH новая фраза перехватывает очередь,
 * и слушатель не сообщает о завершении перехваченной прежней фразы — её
 * колбэк не вызывался, и вызывающий экран (например «Перевод») оставался
 * с залипшим флагом «озвучиваем», пока озвучка шла с другого экрана.
 *
 * Решение: при старте новой фразы все прежние pending-колбэки изымаются из
 * реестра ([start]) и вызываются вызывающим кодом; [finish] атомарно отдаёт
 * колбэк только ещё незавершённой фразы (старый utteranceId перехваченной
 * фразы получает null — состояние текущей не трогается).
 *
 * Реестр потокобезопасен: [start] вызывается из [TtsRuntime.speak] (поток
 * вызывающего кода), [finish] — из фонового потока UtteranceProgressListener;
 * атомарный remove гарантирует однократную выдачу колбэка.
 */
internal class TtsUtteranceRegistry {

    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    /**
     * Зарегистрировать новую фразу [utteranceId] с колбэком [onDone]
     * (null — колбэк не нужен). Возвращает колбэки всех прежних
     * незавершённых фраз — вызывающий код обязан известить их экраны
     * о завершении (QUEUE_FLUSH снял их из очереди).
     */
    fun start(utteranceId: String, onDone: (() -> Unit)?): List<() -> Unit> {
        val previous = drain()
        if (onDone != null) callbacks[utteranceId] = onDone
        return previous
    }

    /**
     * Изымать и вернуть колбэк незавершённой фразы [utteranceId];
     * null — фраза уже завершена, перехвачена QUEUE_FLUSH или неизвестна.
     */
    fun finish(utteranceId: String?): (() -> Unit)? =
        if (utteranceId == null) null else callbacks.remove(utteranceId)

    /**
     * Атомарно изъять и вернуть колбэки всех незавершённых фраз
     * (разгрузка движка: слушатель после shutdown их не вызовет —
     * вызывающий код обязан известить экраны о завершении).
     */
    fun drain(): List<() -> Unit> {
        val taken = mutableListOf<() -> Unit>()
        for (id in callbacks.keys().toList()) {
            callbacks.remove(id)?.let { taken.add(it) }
        }
        return taken
    }
}
