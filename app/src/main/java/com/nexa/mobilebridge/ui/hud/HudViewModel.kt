package com.nexa.mobilebridge.ui.hud

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobilebridge.CommandHistoryRecord
import com.nexa.mobilebridge.NexaBridgeService
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.core.sensor.GeofenceManager
import com.nexa.mobilebridge.core.usage.AppUsageTracker
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.data.logs.LogStreamStatus
import com.nexa.mobilebridge.data.logs.SidecarLogsClient
import com.nexa.mobilebridge.data.terminal.SidecarTerminalClient
import com.nexa.mobilebridge.data.terminal.TerminalSessionStatus
import com.nexa.mobilebridge.protocol.TelemetryReport
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.net.URI
import javax.inject.Inject
import kotlin.math.roundToInt

data class PermissionSnapshot(
    val hasUsageStats: Boolean = false,
    val hasNotifListener: Boolean = false
)

sealed interface HudUiState {
    data object Loading : HudUiState
    data class Active(
        val connectionState: ConnectionState = ConnectionState.Disconnected(null),
        val serverHost: String = "",
        val displayRefreshRateHz: Int = 60,
        val lastTelemetry: TelemetryReport? = null,
        val recentCommands: List<CommandHistoryRecord> = emptyList(),
        val isServiceRunning: Boolean = false,
        val isPowerLockActive: Boolean = false,
        val permissions: PermissionSnapshot = PermissionSnapshot(),
        val isSidecarActive: Boolean = false,
        val sidecarStatusLabel: String = "Standby",
        val geofenceSummary: String = "No zones"
    ) : HudUiState
}

@HiltViewModel
class HudViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val webSocketClient: NexaWebSocketClient,
    private val preferencesDataStore: NexaPreferencesDataStore,
    private val sidecarLogsClient: SidecarLogsClient,
    private val sidecarTerminalClient: SidecarTerminalClient
) : ViewModel() {

    private val _permissions = MutableStateFlow(readPermissions())
    private val _geofenceSummary = MutableStateFlow(readGeofenceSummary())
    private val refreshRateHz = readDisplayRefreshRateHz(context)

    private val serviceAndPowerFlow = combine(
        NexaBridgeService.isServiceRunning,
        NexaBridgeService.isPowerLockActive,
        NexaBridgeService.latestTelemetry
    ) { running, powerLockActive, telemetry ->
        Triple(running, powerLockActive, telemetry)
    }

    private val sidecarAndContextFlow = combine(
        sidecarLogsClient.status,
        sidecarTerminalClient.sessionState,
        _permissions,
        _geofenceSummary
    ) { logStatus, termState, perms, geoSummary ->
        val logsActive = logStatus == LogStreamStatus.LIVE || logStatus == LogStreamStatus.PAUSED
        val termActive = termState.status == TerminalSessionStatus.ACTIVE ||
            termState.status == TerminalSessionStatus.PAUSED_SCREEN_OFF ||
            termState.status == TerminalSessionStatus.DETACHED_RECONNECTING
        val connecting = logStatus == LogStreamStatus.CONNECTING ||
            logStatus == LogStreamStatus.AWAITING_BIOMETRIC ||
            termState.status == TerminalSessionStatus.CONNECTING ||
            termState.status == TerminalSessionStatus.AWAITING_BIOMETRIC

        val isSidecarActive = logsActive || termActive
        val sidecarLabel = when {
            logsActive && termActive -> "Logs & Shell active"
            logsActive -> if (logStatus == LogStreamStatus.PAUSED) "Logs paused" else "Logs active"
            termActive -> if (termState.status == TerminalSessionStatus.ACTIVE) "Shell active" else "Shell paused"
            connecting -> "Connecting..."
            else -> "Standby"
        }
        SidecarAndContextSnapshot(
            isSidecarActive = isSidecarActive,
            sidecarStatusLabel = sidecarLabel,
            permissions = perms,
            geofenceSummary = geoSummary
        )
    }

    private data class SidecarAndContextSnapshot(
        val isSidecarActive: Boolean,
        val sidecarStatusLabel: String,
        val permissions: PermissionSnapshot,
        val geofenceSummary: String
    )

    val uiState: StateFlow<HudUiState> = combine(
        webSocketClient.connectionState,
        preferencesDataStore.serverUrlFlow,
        serviceAndPowerFlow,
        NexaBridgeService.commandHistory,
        sidecarAndContextFlow
    ) { connState, serverUrl, serviceTriple, commands, sidecarCtx ->
        val (isRunning, isPowerLocked, telemetry) = serviceTriple
        HudUiState.Active(
            connectionState = connState,
            serverHost = extractHostWithPort(serverUrl),
            displayRefreshRateHz = refreshRateHz,
            lastTelemetry = telemetry,
            recentCommands = commands,
            isServiceRunning = isRunning,
            isPowerLockActive = isPowerLocked,
            permissions = sidecarCtx.permissions,
            isSidecarActive = sidecarCtx.isSidecarActive,
            sidecarStatusLabel = sidecarCtx.sidecarStatusLabel,
            geofenceSummary = sidecarCtx.geofenceSummary
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HudUiState.Loading
    )

    fun refreshPermissions() {
        _permissions.value = readPermissions()
        _geofenceSummary.value = readGeofenceSummary()
    }

    fun triggerReconnect() {
        NexaBridgeService.reconnect()
    }

    private fun readGeofenceSummary(): String {
        return try {
            val zones = GeofenceManager(context).getAllGeofences()
            when {
                zones.isEmpty() -> "No zones"
                zones.size == 1 -> "1 zone (${zones.first().name})"
                else -> "${zones.size} zones (${zones.first().name})"
            }
        } catch (_: Exception) {
            "No zones"
        }
    }

    private fun readPermissions(): PermissionSnapshot {
        val hasUsage = AppUsageTracker.hasUsageStatsPermission(context)
        val hasNotif = isNotificationListenerEnabled(context)
        return PermissionSnapshot(
            hasUsageStats = hasUsage,
            hasNotifListener = hasNotif
        )
    }

    private fun isNotificationListenerEnabled(ctx: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            enabled.contains(ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun readDisplayRefreshRateHz(ctx: Context): Int {
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.refreshRate?.roundToInt()?.takeIf { it > 0 } ?: 60
        } catch (_: Exception) {
            60
        }
    }

    private fun extractHostWithPort(rawUrl: String): String {
        return try {
            val uri = URI(rawUrl.trim())
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return rawUrl.trim()
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("wss", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }
            "$host:$port"
        } catch (_: Exception) {
            rawUrl.trim()
        }
    }
}
