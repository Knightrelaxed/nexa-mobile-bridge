package com.nexa.mobilebridge

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.nexa.mobilebridge.ui.hud.HudScreen
import com.nexa.mobilebridge.ui.settings.SettingsScreen

@Composable
fun MainNavigation(
    onToggleService: (Boolean) -> Unit
) {
    val backStack = rememberNavBackStack(Hud)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Hud> {
                HudScreen(
                    onNavigateToSettings = { backStack.add(Settings) },
                    onToggleService = onToggleService
                )
            }
            entry<Settings> {
                SettingsScreen(
                    onNavigateBack = { backStack.removeLastOrNull() }
                )
            }
        },
    )
}
