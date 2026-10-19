package com.plox.llmtranslate.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Роли surfaceVariant/errorContainer/secondaryContainer и их on-пары доопределены
// согласованно с премиум-тёмной палитрой (ревью #25): карточки «surfaceVariant»
// в истории/избранном, баннеры errorContainer и статусные secondaryContainer не
// должны fall back'ить на базовые M3-значения, выбивающиеся из тона темы.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF7FD1AE),
    background = Color(0xFF0F1419),
    surface = Color(0xFF171E26),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF222B36),
    onSurfaceVariant = Color(0xFFB4BECB),
    secondaryContainer = Color(0xFF1C3B31),
    onSecondaryContainer = Color(0xFFBCEBD4),
    errorContainer = Color(0xFF5C2626),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = Color(0xFF006B4F),
    background = Color(0xFFF5F9FD),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE3E8F0),
    onSurfaceVariant = Color(0xFF44474F),
    secondaryContainer = Color(0xFFBFF0DB),
    onSecondaryContainer = Color(0xFF00201A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

/**
 * Тема приложения.
 * @param dark тёмная тема (null — по системе).
 */
@Composable
fun OfflineTranslatorTheme(
    dark: Boolean? = null,
    content: @Composable () -> Unit
) {
    val isDark = dark ?: isSystemInDarkTheme()
    val colors = if (isDark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
