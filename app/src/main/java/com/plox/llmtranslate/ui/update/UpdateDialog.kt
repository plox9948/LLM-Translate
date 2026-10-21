package com.plox.llmtranslate.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.plox.llmtranslate.R
import com.plox.llmtranslate.domain.update.UpdateUiState

/**
 * Глобальный диалог самообновления (issue #95): показывается поверх NavHost
 * при [UpdateUiState.Available] (showDialog=true из гейтов UpdateStateHost),
 * [UpdateUiState.Downloading] (прогресс) и [UpdateUiState.DownloadFailed]
 * (текст сбоя + «ОК»). [UpdateUiState.Downloaded] диалог не показывает —
 * MainActivity запускает системный установщик.
 */
@Composable
fun UpdateDialog(
    uiState: UpdateUiState,
    viewModel: UpdateHostViewModel
) {
    when (uiState) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Text(stringResource(R.string.update_available_text, uiState.release.version))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.download(uiState.release) }) {
                    Text(stringResource(R.string.update_action_now))
                }
            },
            dismissButton = {
                // Две колонки отказа: «напомнить позже» (snooze 24 ч) и
                // «больше не показывать» (suppress версии) — оба скрывают
                // диалог до появления ещё более новой версии.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.snooze() }) {
                        Text(stringResource(R.string.update_action_later))
                    }
                    TextButton(onClick = {
                        viewModel.suppress(uiState.release.version)
                    }) {
                        Text(stringResource(R.string.update_action_never))
                    }
                }
            }
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = { /* скачивание идёт — закрытие не предусмотрено */ },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.update_downloading_text,
                            uiState.progress
                        )
                    )
                    LinearProgressIndicator(
                        progress = { uiState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                // Скачивание не отменяется: APK остаётся в кэше, следующий
                //Collect/скачивание пересоздаст файл (GitHubReleaseRepository).
            }
        )

        is UpdateUiState.DownloadFailed -> AlertDialog(
            onDismissRequest = { viewModel.dismissDownloadError() },
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(uiState.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDownloadError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )

        else -> Unit
    }
}
