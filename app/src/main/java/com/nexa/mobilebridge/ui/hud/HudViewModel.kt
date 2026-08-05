package com.nexa.mobilebridge.ui.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.TelemetryReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommandLogEntry(
    val id: String,
    val action: String,
    val timestamp: Long
)

sealed interface HudUiState {
    object Loading : HudUiState
    data class Active(
        val connectionState: ConnectionState = ConnectionState.Disconnected(null),
        val lastTelemetry: TelemetryReport? = null,
        val recentCommands: List<CommandLogEntry> = emptyList(),
        val isGodModeActive: Boolean = false,
        val isServiceRunning: Boolean = false
    ) : HudUiState
}

@HiltViewModel
class HudViewModel @Inject constructor(
    private val webSocketClient: NexaWebSocketClient
) : ViewModel() {

    private val _recentCommands = MutableStateFlow<List<CommandLogEntry>>(emptyList())
    private val _isServiceRunning = MutableStateFlow(false)
    private val _lastTelemetry = MutableStateFlow<TelemetryReport?>(null)

    val uiState: StateFlow<HudUiState> = combine(
        webSocketClient.connectionState,
        _recentCommands,
        _isServiceRunning,
        _lastTelemetry
    ) { connState, commands, isServiceRunning, telemetry ->
        HudUiState.Active(
            connectionState = connState,
            lastTelemetry = telemetry,
            recentCommands = commands,
            isGodModeActive = false, // TODO: Observe from a GodMode state manager if applicable
            isServiceRunning = isServiceRunning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HudUiState.Loading
    )

    init {
        viewModelScope.launch {
            webSocketClient.incomingCommands.collect { command ->
                val entry = CommandLogEntry(command.command_id, command.action, command.timestamp)
                _recentCommands.update { current ->
                    (listOf(entry) + current).take(10) // Keep last 10
                }
            }
        }
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
}
