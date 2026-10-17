package com.plox.llmtranslate.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.plox.llmtranslate.R

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

/**
 * Иконка звезды избранного (добавить/убрать) — общий блок карточек экранов
 * «Перевод» и «История»: закрашенная звезда и подпись «убрать» у избранной
 * записи, контурная и «добавить» — у обычной.
 */
@Composable
internal fun FavoriteStarIcon(isFavorite: Boolean) {
    Icon(
        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
        contentDescription = stringResource(
            if (isFavorite) R.string.favorites_remove_cd
            else R.string.favorites_add_cd
        ),
        tint = if (isFavorite) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}
