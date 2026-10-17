package com.plox.llmtranslate.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Подпись направления «ИСТОЧНИК → ЦЕЛЬ» (коды языков заглавными буквами) —
 * общий блок карточек вкладок «История» и «Избранное». Встраивается в Row
 * вызывающей карточки.
 */
@Composable
internal fun LanguageDirection(sourceLang: String, targetLang: String) {
    Text(
        text = sourceLang.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = targetLang.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
    )
}
