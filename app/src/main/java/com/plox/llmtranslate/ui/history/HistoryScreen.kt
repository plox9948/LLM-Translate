package com.plox.llmtranslate.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plox.llmtranslate.R
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.ui.LanguageDirection

/**
 * Вкладка «История»: список последних переводов. Тап по записи подставляет её
 * на экран «Перевод» ([onUseEntry] выполняет переход туда).
 */
@Composable
fun HistoryScreen(
    onUseEntry: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()

    if (history.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.nav_history),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::clearAll) {
                Text(stringResource(R.string.clear))
            }
            }
        }
        items(history, key = { it.id }) { entry ->
            HistoryItem(
                entry = entry,
                isFavorite = favoriteKeys.contains(
                    FavoriteEntry.key(entry.sourceLang, entry.targetLang, entry.inputText)
                ),
                onUse = {
                    viewModel.useEntry(entry)
                    onUseEntry()
                },
                onToggleFavorite = { viewModel.toggleFavorite(entry) },
                onDelete = { viewModel.deleteEntry(entry.id) }
            )
        }
    }
}

/**
 * Одна запись истории: направление, фрагменты текста, звёздочка избранного
 * и кнопка удаления. Клик по карточке подставляет запись на экран «Перевод».
 */
@Composable
private fun HistoryItem(
    entry: TranslationHistoryEntry,
    isFavorite: Boolean,
    onUse: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUse),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanguageDirection(entry.sourceLang, entry.targetLang)
                Spacer(modifier = Modifier.weight(1f))
                // Избранное записи (issue #38).
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.width(40.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star
                        else Icons.Outlined.Star,
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
                IconButton(onClick = onDelete, modifier = Modifier.width(40.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.translate_history_delete_cd)
                    )
                }
            }
            Text(
                text = entry.inputText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.resultText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
