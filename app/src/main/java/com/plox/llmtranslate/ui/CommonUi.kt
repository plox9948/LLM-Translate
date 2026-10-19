package com.plox.llmtranslate.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.plox.llmtranslate.R
import java.util.Locale

/**
 * Диалог «Ошибка» с одной кнопкой «ОК» (общий для экранов «Перевод»,
 * «История», «Избранное»): [onDismiss] вызывается и закрытием диалога,
 * и кнопкой — это сброс состояния ошибки во ViewModel.
 */
@Composable
internal fun ErrorDialog(message: String?, onDismiss: () -> Unit) {
    message?.let { text ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

/** Диалог «Ошибка» для текста, заданного строковым ресурсом (История/Избранное). */
@Composable
internal fun ErrorDialog(@StringRes messageRes: Int?, onDismiss: () -> Unit) {
    messageRes?.let { ErrorDialog(stringResource(it), onDismiss) }
}

/**
 * Баннер ошибки/предупреждения на весь экран (общий для экранов «Модели»
 * и «Настройки»).
 * @param onClick необязательный обработчик тапа (скрытие баннера).
 */
@Composable
internal fun ErrorBanner(text: String, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) { content() }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) { content() }
    }
}

// Пороги форматирования размера (КиБ/МиБ/ГиБ в байтах).
private const val BYTES_IN_KB = 1_024L
private const val BYTES_IN_MB = 1_048_576L
private const val BYTES_IN_GB = 1_073_741_824L

/**
 * Человекочитаемый размер байт (КиБ/МиБ/ГиБ с локализованными единицами) —
 * общий для экранов «Модели» и «Перевод» (ревью #19).
 */
@Composable
internal fun formatBytes(bytes: Long): String {
    val unitGb = stringResource(R.string.unit_gb)
    val unitMb = stringResource(R.string.unit_mb)
    val unitKb = stringResource(R.string.unit_kb)
    return when {
        bytes >= BYTES_IN_GB -> String.format(Locale.US, "%.1f %s", bytes / BYTES_IN_GB.toDouble(), unitGb)
        bytes >= BYTES_IN_MB -> String.format(Locale.US, "%.0f %s", bytes / BYTES_IN_MB.toDouble(), unitMb)
        else -> String.format(Locale.US, "%.0f %s", bytes / BYTES_IN_KB.toDouble(), unitKb)
    }
}

/**
 * Пустое состояние списка: текст по центру своей области (вкладки «История»
 * и «Избранное»). [modifier] задаёт область (на весь экран или остаток
 * колонки под полем поиска).
 */
@Composable
internal fun EmptyListPlaceholder(
    text: String,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
