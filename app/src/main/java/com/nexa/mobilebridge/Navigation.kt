package com.nexa.mobilebridge

import android.content.Intent
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.ui.call.FakeCallActivity
import com.nexa.mobilebridge.ui.chat.ChatScreen
import com.nexa.mobilebridge.ui.chat.ChatSelectionTopBar
import com.nexa.mobilebridge.ui.chat.ChatViewModel
import com.nexa.mobilebridge.ui.components.NexaBottomNavBar
import com.nexa.mobilebridge.ui.components.NexaTopBar
import com.nexa.mobilebridge.ui.hud.HudScreen
import com.nexa.mobilebridge.ui.hud.HudUiState
import com.nexa.mobilebridge.ui.hud.HudViewModel
import com.nexa.mobilebridge.ui.logs.LogViewerScreen
import com.nexa.mobilebridge.ui.settings.SettingsScreen
import com.nexa.mobilebridge.ui.terminal.TerminalScreen

@Composable
fun MainNavigation(
    onToggleService: (Boolean) -> Unit
) {
    val backStack = rememberNavBackStack(MainShell)
    var activeTab by rememberSaveable { mutableStateOf(NexaMainTab.CHAT) }
    val context = LocalContext.current

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        },
        entryProvider = entryProvider {
            entry<MainShell> {
                val hudViewModel: HudViewModel = hiltViewModel()
                val chatViewModel: ChatViewModel = hiltViewModel()

                val hudState by hudViewModel.uiState.collectAsStateWithLifecycle()
                val effectiveAvatarState by chatViewModel.effectiveAvatarState.collectAsStateWithLifecycle()
                val chatSelection by chatViewModel.selectionState.collectAsStateWithLifecycle()
                val chatPrefs by chatViewModel.chatPrefs.collectAsStateWithLifecycle()
                val connectionState = (hudState as? HudUiState.Active)?.connectionState
                    ?: ConnectionState.Disconnected(null)
                val shellView = LocalView.current

                LaunchedEffect(activeTab) {
                    if (activeTab != NexaMainTab.CHAT) {
                        chatViewModel.clearSelection()
                    }
                }

                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val isTerminalTab = activeTab == NexaMainTab.SHELL
                val isLogsTab = activeTab == NexaMainTab.LOGS
                val showChatSelectionBar = activeTab == NexaMainTab.CHAT && chatSelection.isActive

                Scaffold(
                    topBar = {
                        // PRD §12.1 / Stitch v2: Both SHELL (04) and LOGS (03) render their own unified single top header
                        if (showChatSelectionBar) {
                            ChatSelectionTopBar(
                                selectedCount = chatSelection.count,
                                canReply = chatSelection.canReply,
                                canCopy = chatSelection.canCopy,
                                onClear = {
                                    if (chatPrefs.hapticEnabled) {
                                        shellView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    chatViewModel.clearSelection()
                                },
                                onReply = {
                                    if (chatPrefs.hapticEnabled) {
                                        shellView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    chatViewModel.replyToSelected()
                                },
                                onCopy = {
                                    if (chatPrefs.hapticEnabled) {
                                        shellView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    chatViewModel.copySelected()
                                },
                                onDelete = {
                                    if (chatPrefs.hapticEnabled) {
                                        shellView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    chatViewModel.requestDeleteSelected()
                                }
                            )
                        } else if (!isTerminalTab && !isLogsTab) {
                            NexaTopBar(
                                activeTab = activeTab,
                                connectionState = connectionState,
                                onOpenSettings = { if (backStack.lastOrNull() != Settings) backStack.add(Settings) },
                                avatarState = effectiveAvatarState,
                                onStartVoiceCall = {
                                    val intent = Intent(context, FakeCallActivity::class.java).apply {
                                        action = "com.nexa.mobilebridge.ACTION_CALL_NEXA"
                                        putExtra("IS_OUTGOING_CALL", true)
                                        putExtra("caller_name", "N.E.X.A")
                                        putExtra("call_reason", "Live Voice Session")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    },
                    bottomBar = {
                        // U-M10: Scope IME inset reading inside a small composable so IME animation does not recompose MainShell
                        AdaptiveBottomNavBar(
                            activeTab = activeTab,
                            isTerminalTab = isTerminalTab,
                            isLandscape = isLandscape,
                            onSelectTab = { activeTab = it }
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (activeTab) {
                            NexaMainTab.CHAT -> ChatScreen(viewModel = chatViewModel)
                            NexaMainTab.HUD -> HudScreen(
                                viewModel = hudViewModel,
                                onToggleService = onToggleService
                            )
                            NexaMainTab.LOGS -> LogViewerScreen(
                                onOpenSettings = { if (backStack.lastOrNull() != Settings) backStack.add(Settings) }
                            )
                            NexaMainTab.SHELL -> TerminalScreen(
                                onOpenSettings = { if (backStack.lastOrNull() != Settings) backStack.add(Settings) },
                                isLandscape = isLandscape
                            )
                        }
                    }
                }
            }

            entry<Settings> {
                SettingsScreen(
                    onNavigateBack = { backStack.removeLastOrNull() }
                )
            }
        }
    )
}

@Composable
private fun AdaptiveBottomNavBar(
    activeTab: NexaMainTab,
    isTerminalTab: Boolean,
    isLandscape: Boolean,
    onSelectTab: (NexaMainTab) -> Unit
) {
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    // PRD §12.1 / Audit A5: Hide bottom nav when keyboard is open OR in landscape immersive terminal
    if (!isImeVisible && !(isTerminalTab && isLandscape)) {
        NexaBottomNavBar(
            activeTab = activeTab,
            onSelectTab = onSelectTab
        )
    }
}
