package com.plox.llmtranslate.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Memory
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.plox.llmtranslate.R
import com.plox.llmtranslate.ui.models.ModelsScreen
import com.plox.llmtranslate.ui.settings.SettingsScreen
import com.plox.llmtranslate.ui.translate.TranslateScreen

/**
 * Экраны приложения.
 */
enum class Screen(val route: String, @StringRes val titleRes: Int, val icon: ImageVector) {
    TRANSLATE("translate", R.string.nav_translate, Icons.Filled.Translate),
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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
            composable(Screen.TRANSLATE.route) { TranslateScreen() }
            composable(Screen.MODELS.route) { ModelsScreen() }
            composable(Screen.SETTINGS.route) { SettingsScreen() }
        }
    }
}
