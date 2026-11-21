package com.nexa.mobilebridge

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

enum class NexaMainTab(val label: String, val subtitle: String) {
    CHAT("Chat", "AI CONTROL"),
    HUD("HUD", "BRIDGE CONTROL"),
    LOGS("Logs", "SERVER LOGS"),
    SHELL("Shell", "TERMINAL PTY")
}

@Serializable data object MainShell : NavKey
@Serializable data object BridgeStatus : NavKey
@Serializable data object Settings : NavKey
@Serializable data object ServerLogs : NavKey
@Serializable data object RemoteTerminal : NavKey
