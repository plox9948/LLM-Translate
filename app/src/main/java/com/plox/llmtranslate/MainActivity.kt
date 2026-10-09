package com.plox.llmtranslate

import android.content.res.Configuration
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.ui.MainScreen
import com.plox.llmtranslate.ui.theme.OfflineTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    }

    @Inject
    lateinit var modelManager: ModelManager

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
        val savedLanguage = runBlocking { preferences.uiLanguage.first() }
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
            val theme by preferences.theme.collectAsState(initial = "system")

            OfflineTranslatorTheme(dark = resolveDark(theme)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }

        // При сворачивании приложения освобождаем память: разгружаем рантаймы.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                modelManager.releaseAllRuntimes()
            }
        })
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
     */
    private fun applyUiLanguage(lang: String) {
        if (lang == appliedUiLanguage) return
        appliedUiLanguage = lang
        val config = when (lang) {
            "ru" -> Configuration(resources.configuration).apply { setLocale(Locale("ru")) }
            "en" -> Configuration(resources.configuration).apply { setLocale(Locale("en")) }
            // «По системе» — явный сброс на снимок системной конфигурации:
            // без него процесс сохраняет прилипшую локаль от updateConfiguration.
            else -> baseConfig ?: return
        }
        // updateConfiguration устарел, но остаётся надёжным способом применить
        // локаль к активности без AppCompat. Современный createConfigurationContext
        // не меняет ресурсы самой активности.
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
