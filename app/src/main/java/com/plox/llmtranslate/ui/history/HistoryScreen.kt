package com.plox.llmtranslate.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plox.llmtranslate.R
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.ui.EmptyListPlaceholder
import com.plox.llmtranslate.ui.ErrorDialog
import com.plox.llmtranslate.ui.FavoriteStarIcon
import com.plox.llmtranslate.ui.TranslationEntryCard

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
    val errorMessageRes by viewModel.errorMessageRes.collectAsStateWithLifecycle()

    // Сбой репозиторной операции (ревью #65): диалог как на экране «Перевод».
    ErrorDialog(errorMessageRes, viewModel::clearError)

    if (history.isEmpty()) {
        EmptyListPlaceholder(stringResource(R.string.history_empty))
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
 * Каркас общий с избранного — [TranslationEntryCard].
 */
@Composable
private fun HistoryItem(
    entry: TranslationHistoryEntry,
    isFavorite: Boolean,
    onUse: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    TranslationEntryCard(
        sourceLang = entry.sourceLang,
        targetLang = entry.targetLang,
        inputText = entry.inputText,
        resultText = entry.resultText,
        onUse = onUse
    ) {
        // Избранное записи (issue #38).
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.width(40.dp)
        ) {
            FavoriteStarIcon(isFavorite)
        }
        IconButton(onClick = onDelete, modifier = Modifier.width(40.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.translate_history_delete_cd)
            )
        }
    }
}
