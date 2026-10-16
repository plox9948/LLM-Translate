package com.plox.llmtranslate

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.BackgroundUnloadScheduler
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.defaultUiLanguageForSystemLocale
import com.plox.llmtranslate.ui.MainScreen
import com.plox.llmtranslate.ui.theme.OfflineTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Единственная активность: хост для Compose-экранов.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Исходная системная конфигурация, снятая при первом создании активности
         * в процессе. После updateConfiguration() локаль «прилипает» к процессу и
         * наследуется при recreate(), поэтому для возврата «по системе» нужен
         * явный сброс на этот снимок.
         */
        private var baseConfig: Configuration? = null

        /**
         * ModelManager последней созданной активности (Hilt-синглтон процесса).
         * Процессный observer держит эту ссылку, а не экземпляр активности,
         * поэтому уничтоженные активности не удерживаются.
         */
        @Volatile
        private var releaseTarget: ModelManager? = null

        /**
         * Планировщик отложенной выгрузки последней созданной активности
         * (Hilt-синглтон процесса). Обновляется вместе с [releaseTarget].
         */
        @Volatile
        private var backgroundUnload: BackgroundUnloadScheduler? = null

        /**
         * Единственный на процесс observer разгрузки: регистрируется один раз
         * при первом создании активности и не снимается. Анонимный observer
         * в каждом onCreate() накапливал наблюдателей, удерживая уничтоженные
         * активности и многократно вызывая release() по ON_STOP.
         *
         * ON_STOP — планирует отложенную выгрузку с тайм-аутом из настроек
         * (читается на момент сворачивания); ON_START процесса — отменяет её,
         * если пользователь вернулся до истечения тайм-аута.
         */
        private val processReleaseObserver = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                backgroundUnload?.cancel()
            }

            override fun onStop(owner: LifecycleOwner) {
                val target = releaseTarget
                val scheduler = backgroundUnload
                if (target == null || scheduler == null) return
                scheduler.schedule(target::releaseAllRuntimes)
            }
        }
    }

    @Inject
    lateinit var modelManager: ModelManager

    @Inject
    lateinit var unloadScheduler: BackgroundUnloadScheduler

    @Inject
    lateinit var preferences: ModelPreferences

    /** Последний применённый язык: защита от повторного apply и цикла recreate. */
    private var appliedUiLanguage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Снимаем снимок системной конфигурации в первой генерации активности
        // процесса — она ещё не изменена updateConfiguration().
        if (baseConfig == null) baseConfig = Configuration(resources.configuration)

        // Язык применяем синхронно ДО первой композиции: stringResource читает
        // ресурсы уже с нужной локалью, первый кадр уже на выбранном языке.
        // prefetch запущен в OfflineTranslatorApp.onCreate — здесь читаем из
        // памяти; при recreate после смены языка кэш уже прогрет, поэтому
        // главная нить не блокируется на IO.DataStore.
        val savedLanguage = runBlocking { preferences.awaitUiLanguageCache() }
        applyUiLanguage(savedLanguage)

        // Смена языка вне композиции: пересоздаём активность, чтобы все
        // ресурсы перечитались. Сравнение с appliedUiLanguage защищает от
        // цикла recreate (после пересоздания значение уже применено).
        lifecycleScope.launch {
            preferences.uiLanguage
                .distinctUntilChanged()
                .collect { lang ->
                    if (lang != appliedUiLanguage) recreate()
                }
        }

        setContent {
            val theme by preferences.theme.collectAsStateWithLifecycle(initialValue = "system")

            OfflineTranslatorTheme(dark = resolveDark(theme)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }

        // При сворачивании процесса планируем отложенную выгрузку рантаймов
        // (тайм-аут из настроек), при возврате — отменяем. Ссылки на синглтоны
        // обновляются при каждом создании активности; observer единственный
        // на процесс (регистрируется один раз).
        releaseTarget = modelManager
        backgroundUnload = unloadScheduler
        ProcessLifecycleOwner.get().lifecycle.addObserver(processReleaseObserver)
    }

    /**
     * Тёмная тема по настройке: true/false/null (по системе).
     */
    private fun resolveDark(theme: String): Boolean? = when (theme) {
        "dark" -> true
        "light" -> false
        else -> null
    }

    /**
     * Применить язык интерфейса к конфигурации активности.
     *
     * Явные значения («ru»/«en»/«ne») применяются как есть. В режиме «по системе»
     * язык берётся из снимка системной конфигурации [baseConfig] и детерминированно
     * отображается в один из поддерживаемых языков (см. [defaultUiLanguageForSystemLocale]),
     * после чего применяются той же веткой setLocale — прямой сброс на baseConfig
     * дал бы произвольную системную локаль, недоступную в интерфейсе.
     */
    private fun applyUiLanguage(lang: String) {
        if (lang == appliedUiLanguage) return
        appliedUiLanguage = lang
        val config = when (lang) {
            "ru" -> Configuration(resources.configuration).apply { setLocale(Locale("ru")) }
            "en" -> Configuration(resources.configuration).apply { setLocale(Locale("en")) }
            "ne" -> Configuration(resources.configuration).apply { setLocale(Locale("ne")) }
            // «По системе» — берём язык системы из снимка конфигурации и
            // отображаем его в поддерживаемый язык UI. Детерминированность
            // важна: без этого процесс сохраняет прилипшую локаль от
            // предыдущего updateConfiguration и наследует её при recreate().
            else -> {
                val systemLanguage = baseConfig?.let { snapshot ->
                    @Suppress("DEPRECATION")
                    val locales = snapshot.locales
                    if (locales != null && locales.size() > 0) locales.get(0).language
                    else snapshot.locale?.language
                }
                val mapped = defaultUiLanguageForSystemLocale(systemLanguage)
                Configuration(resources.configuration).apply { setLocale(Locale(mapped)) }
            }
        }
        // updateConfiguration устарел, но остаётся надёжным способом применить
        // локаль к активности без AppCompat. Современный createConfigurationContext
        // не меняет ресурсы самой активности.
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
