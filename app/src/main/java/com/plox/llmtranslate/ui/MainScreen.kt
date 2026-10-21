package com.plox.llmtranslate.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.plox.llmtranslate.R
import com.plox.llmtranslate.domain.update.UpdateUiState
import com.plox.llmtranslate.ui.favorites.FavoritesScreen
import com.plox.llmtranslate.ui.history.HistoryScreen
import com.plox.llmtranslate.ui.models.ModelsScreen
import com.plox.llmtranslate.ui.settings.SettingsScreen
import com.plox.llmtranslate.ui.translate.TranslateScreen
import com.plox.llmtranslate.ui.update.UpdateDialog
import com.plox.llmtranslate.ui.update.UpdateHostViewModel

/**
 * Экраны приложения.
 */
enum class Screen(val route: String, @StringRes val titleRes: Int, val icon: ImageVector) {
    TRANSLATE("translate", R.string.nav_translate, Icons.Filled.Translate),
    HISTORY("history", R.string.nav_history, Icons.Filled.History),
    FAVORITES("favorites", R.string.nav_favorites, Icons.Filled.Star),
    MODELS("models", R.string.nav_models, Icons.Filled.Memory),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings)
}

/**
 * Главный экран с нижней навигацией.
 */
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Единый режим перехода между вкладками: одна запись на таб в стопке,
    // с сохранением/восстановлением состояния экрана.
    val tabMode: NavOptionsBuilder.() -> Unit = {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun navigateTab(route: String) = navController.navigate(route) { tabMode() }

    // Глобальный хост самообновления (issue #95): состояние — синглтон
    // UpdateStateHost за Hilt-ViewModel; диалог живёт поверх NavHost на
    // любом экране (показывается по showDialog из гейтов UpdateStateHost).
    val updateViewModel: UpdateHostViewModel = hiltViewModel()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    val updateDialogVisible = when (val state = updateState) {
        is UpdateUiState.Available -> state.showDialog
        is UpdateUiState.Downloading -> true
        is UpdateUiState.DownloadFailed -> true
        else -> false
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = { navigateTab(screen.route) },
                        icon = {
                            Icon(screen.icon, contentDescription = stringResource(screen.titleRes))
                        },
                        label = { Text(stringResource(screen.titleRes)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.TRANSLATE.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.TRANSLATE.route) {
                TranslateScreen()
            }
            composable(Screen.HISTORY.route) {
                HistoryScreen(
                    onUseEntry = { navigateTab(Screen.TRANSLATE.route) }
                )
            }
            composable(Screen.FAVORITES.route) {
                FavoritesScreen(
                    onUseEntry = { navigateTab(Screen.TRANSLATE.route) }
                )
            }
            composable(Screen.MODELS.route) { ModelsScreen() }
            composable(Screen.SETTINGS.route) { SettingsScreen() }
        }

        // Диалог обновления поверх NavHost на любой вкладке (issue #95);
        // Downloaded не показывается — установщик запускает MainActivity.
        if (updateDialogVisible) {
            UpdateDialog(
                uiState = updateState,
                viewModel = updateViewModel
            )
        }
    }
}

