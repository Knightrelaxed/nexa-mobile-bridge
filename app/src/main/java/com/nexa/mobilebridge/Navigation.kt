package com.nexa.mobilebridge

import android.content.Intent
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.data.chat.ChatVisibilityTracker
import com.nexa.mobilebridge.ui.call.FakeCallActivity
import com.nexa.mobilebridge.ui.chat.ChatScreen
import com.nexa.mobilebridge.ui.chat.ChatSelectionTopBar
import com.nexa.mobilebridge.ui.chat.ChatViewModel
import com.nexa.mobilebridge.ui.components.NexaTopBar
import com.nexa.mobilebridge.ui.hud.HudScreen
import com.nexa.mobilebridge.ui.hud.HudUiState
import com.nexa.mobilebridge.ui.hud.HudViewModel
import com.nexa.mobilebridge.ui.logs.LogViewerScreen
import com.nexa.mobilebridge.ui.settings.SettingsScreen
import com.nexa.mobilebridge.ui.terminal.TerminalScreen

private const val NAV_FORWARD_DURATION_MS = 280
private const val NAV_POP_DURATION_MS = 250
private const val NAV_PREDICTIVE_POP_DURATION_MS = 260

@Composable
fun MainNavigation(
    chatVisibilityTracker: ChatVisibilityTracker,
    onToggleService: (Boolean) -> Unit
) {
    val backStack = rememberNavBackStack(MainShell)
    val context = LocalContext.current
    val openTabRequest by chatVisibilityTracker.openTabRequest.collectAsStateWithLifecycle()

    fun navigateSingleTop(destination: NavKey) {
        if (destination == MainShell) {
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            return
        }
        if (backStack.lastOrNull() == destination) return
        // Keep backStack at max 2 levels ([MainShell, ActiveSecondaryScreen]) so previous
        // secondary screens and their GPU RenderNodes are disposed immediately.
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        backStack.add(destination)
    }

    fun startVoiceCall() {
        val intent = Intent(context, FakeCallActivity::class.java).apply {
            action = "com.nexa.mobilebridge.ACTION_CALL_NEXA"
            putExtra("IS_OUTGOING_CALL", true)
            putExtra("caller_name", "N.E.X.A")
            putExtra("call_reason", "Live Voice Session")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    LaunchedEffect(openTabRequest) {
        val requested = openTabRequest ?: return@LaunchedEffect
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        when (requested) {
            NexaMainTab.CHAT -> Unit
            NexaMainTab.HUD -> navigateSingleTop(BridgeStatus)
            NexaMainTab.LOGS -> navigateSingleTop(ServerLogs)
            NexaMainTab.SHELL -> navigateSingleTop(RemoteTerminal)
        }
        chatVisibilityTracker.consumeOpenTabRequest()
    }

    val topRoute = backStack.lastOrNull()
    LaunchedEffect(topRoute) {
        val activeTab = when (topRoute) {
            MainShell -> NexaMainTab.CHAT
            BridgeStatus -> NexaMainTab.HUD
            ServerLogs -> NexaMainTab.LOGS
            RemoteTerminal -> NexaMainTab.SHELL
            else -> null
        }
        chatVisibilityTracker.setVisibleTab(activeTab)
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        },
        transitionSpec = {
            slideInHorizontally(
                animationSpec = tween(NAV_FORWARD_DURATION_MS, easing = FastOutSlowInEasing),
                initialOffsetX = { fullWidth -> fullWidth }
            ) togetherWith slideOutHorizontally(
                animationSpec = tween(NAV_FORWARD_DURATION_MS, easing = FastOutSlowInEasing),
                targetOffsetX = { fullWidth -> -fullWidth / 4 }
            )
        },
        popTransitionSpec = {
            slideInHorizontally(
                animationSpec = tween(NAV_POP_DURATION_MS, easing = FastOutSlowInEasing),
                initialOffsetX = { fullWidth -> -fullWidth / 4 }
            ) togetherWith slideOutHorizontally(
                animationSpec = tween(NAV_POP_DURATION_MS, easing = FastOutSlowInEasing),
                targetOffsetX = { fullWidth -> fullWidth }
            )
        },
        predictivePopTransitionSpec = { swipeEdge ->
            // Track thumb direction physically: left-edge swipe slides right; right-edge swipe slides left.
            val exitDirection = if (swipeEdge == NavigationEvent.EDGE_RIGHT) -1 else 1
            slideInHorizontally(
                animationSpec = tween(NAV_PREDICTIVE_POP_DURATION_MS, easing = LinearEasing),
                initialOffsetX = { fullWidth -> -exitDirection * (fullWidth / 4) }
            ) togetherWith slideOutHorizontally(
                animationSpec = tween(NAV_PREDICTIVE_POP_DURATION_MS, easing = LinearEasing),
                targetOffsetX = { fullWidth -> exitDirection * fullWidth }
            )
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

                Scaffold(
                    topBar = {
                        if (chatSelection.isActive) {
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
                        } else {
                            NexaTopBar(
                                connectionState = connectionState,
                                onOpenBridgeStatus = { navigateSingleTop(BridgeStatus) },
                                onOpenSettings = { navigateSingleTop(Settings) },
                                avatarState = effectiveAvatarState,
                                voiceLevelFlow = chatViewModel.voiceVisorLevel,
                                hapticEnabled = chatPrefs.hapticEnabled,
                                onStartVoiceCall = { startVoiceCall() }
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        ChatScreen(viewModel = chatViewModel)
                    }
                }
            }

            entry<BridgeStatus> {
                HudScreen(
                    onToggleService = onToggleService,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onOpenSettings = { navigateSingleTop(Settings) }
                )
            }

            entry<Settings> {
                SettingsScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onOpenBridgeStatus = { navigateSingleTop(BridgeStatus) },
                    onOpenServerLogs = { navigateSingleTop(ServerLogs) },
                    onOpenRemoteTerminal = { navigateSingleTop(RemoteTerminal) }
                )
            }

            entry<ServerLogs> {
                LogViewerScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onOpenSettings = { navigateSingleTop(Settings) }
                )
            }

            entry<RemoteTerminal> {
                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                TerminalScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onOpenSettings = { navigateSingleTop(Settings) },
                    isLandscape = isLandscape
                )
            }
        }
    )
}
