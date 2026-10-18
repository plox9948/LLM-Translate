package com.plox.llmtranslate.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.plox.llmtranslate.R

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
