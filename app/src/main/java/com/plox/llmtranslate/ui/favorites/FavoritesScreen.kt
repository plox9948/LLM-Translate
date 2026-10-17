package com.plox.llmtranslate.ui.favorites

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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.plox.llmtranslate.ui.LanguageDirection

/**
 * Вкладка «Избранное»: избранные переводы с озвучиванием оригинала/перевода.
 * Тап по карточке подставляет запись на экран «Перевод» ([onUseEntry]
 * выполняет переход туда), звезда убирает запись из избранного.
 */
@Composable
fun FavoritesScreen(
    onUseEntry: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()

    // Избранного нет вовсе и поиск не вводился — обычное пустое состояние.
    if (favorites.isEmpty() && searchQuery.isBlank()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.favorites_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            query = searchQuery,
            onQueryChange = viewModel::onQueryChange
        )
        if (favorites.isEmpty()) {
            // Поиск без совпадений — то же идиоматичное пустое состояние,
            // что и в «Истории», но с текстом «Ничего не найдено».
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.favorites_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "header") {
                Text(
                    text = stringResource(R.string.nav_favorites),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(favorites, key = { it.id }) { entry ->
                val inputSelection = SpeakingSelection(entry.id, FavoriteSpeakField.INPUT)
                val resultSelection = SpeakingSelection(entry.id, FavoriteSpeakField.RESULT)
                FavoriteItem(
                    entry = entry,
                    speakingInput = speaking == inputSelection,
                    speakingResult = speaking == resultSelection,
                    onUse = {
                        viewModel.useEntry(entry)
                        onUseEntry()
                    },
                    onSpeak = { field ->
                        // Повторный тап по говорящему полю — стоп, иначе — начать
                        // озвучивание выбранного поля.
                        val selection =
                            if (field == FavoriteSpeakField.INPUT) inputSelection else resultSelection
                        if (speaking == selection) {
                            viewModel.stopSpeaking()
                        } else {
                            viewModel.speak(entry, field)
                        }
                    },
                    onRemove = { viewModel.removeFavorite(entry) }
                )
            }
        }
    }
}

/**
 * Поле поиска по избранному: leading-иконка поиска, trailing-кнопка очистки
 * при непустом запросе. Состояние поднято в [FavoritesViewModel].
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.favorites_search_hint)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.favorites_search_clear_cd)
                    )
                }
            }
        }
    )
}

/**
 * Одна карточка избранного: направление, тексты, озвучивание оригинала и
 * перевода, звезда удаления из избранного.
 */
@Composable
private fun FavoriteItem(
    entry: FavoriteEntry,
    speakingInput: Boolean,
    speakingResult: Boolean,
    onUse: () -> Unit,
    onSpeak: (FavoriteSpeakField) -> Unit,
    onRemove: () -> Unit
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
                // Озвучивание оригинала.
                IconButton(
                    onClick = { onSpeak(FavoriteSpeakField.INPUT) },
                    modifier = Modifier.width(40.dp)
                ) {
                    Icon(
                        imageVector = if (speakingInput) Icons.Filled.Stop
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.favorites_speak_input_cd)
                    )
                }
                // Озвучивание перевода.
                IconButton(
                    onClick = { onSpeak(FavoriteSpeakField.RESULT) },
                    modifier = Modifier.width(40.dp)
                ) {
                    Icon(
                        imageVector = if (speakingResult) Icons.Filled.Stop
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.favorites_speak_result_cd)
                    )
                }
                // Убрать из избранного.
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.width(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.favorites_remove_cd),
                        tint = MaterialTheme.colorScheme.primary
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
