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
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.ui.MainScreen
import com.plox.llmtranslate.ui.theme.OfflineTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * Единственная активность: хост для Compose-экранов.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var modelManager: ModelManager

    @Inject
    lateinit var preferences: ModelPreferences

    /** Последний применённый язык, чтобы не повторять updateConfiguration при рекомпозиции. */
    private var appliedUiLanguage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by preferences.theme.collectAsState(initial = "system")
            val uiLanguage by preferences.uiLanguage.collectAsState(initial = "system")

            // Применяем выбранный язык интерфейса.
            applyUiLanguage(uiLanguage)

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
                modelManager.releaseInactiveRuntimes()
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
        val locale = when (lang) {
            "ru" -> Locale("ru")
            "en" -> Locale("en")
            else -> return // system — не трогать
        }
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        // updateConfiguration устарел, но остаётся надёжным способом применить
        // локаль к активности без пересоздания и без AppCompat. Современный
        // createConfigurationContext не меняет ресурсы самой активности.
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
