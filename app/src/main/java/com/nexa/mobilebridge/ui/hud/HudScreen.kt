package com.nexa.mobilebridge.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobilebridge.core.util.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudScreen(
    viewModel: HudViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit,
    onToggleService: (Boolean) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("N.E.X.A TERMINAL") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (val state = uiState) {
            is HudUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            is HudUiState.Active -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ConnectionStatusBadge(state.connectionState)
                    
                    ServiceToggleButton(
                        isRunning = state.isServiceRunning,
                        onToggle = { 
                            onToggleService(it)
                            viewModel.setServiceRunning(it)
                        }
                    )

                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Text(
                        "COMMAND LOG",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    CommandLogList(state.recentCommands)
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusBadge(state: ConnectionState) {
    val (color, text) = when (state) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.tertiary to "ONLINE - CONNECTED" // using tertiary or green
        is ConnectionState.Connecting -> MaterialTheme.colorScheme.secondary to "CONNECTING..."
        is ConnectionState.Reconnecting -> MaterialTheme.colorScheme.secondary to "RECONNECTING (ATTEMPT ${state.attempt})"
        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.error to "OFFLINE"
        is ConnectionState.AuthFailed -> MaterialTheme.colorScheme.error to "AUTH FAILED"
    }

    // fallback green if tertiary isn't explicitly defined in our tight theme
    val activeColor = if (state is ConnectionState.Connected) Color(0xFF00E676) else color

    Surface(
        color = activeColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, activeColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(8.dp).background(activeColor, RoundedCornerShape(50)))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = activeColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ServiceToggleButton(isRunning: Boolean, onToggle: (Boolean) -> Unit) {
    Button(
        onClick = { onToggle(!isRunning) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(if (isRunning) "STOP N.E.X.A SERVICE" else "START N.E.X.A SERVICE")
    }
}

@Composable
fun CommandLogList(commands: List<CommandLogEntry>) {
    if (commands.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(100.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Waiting for commands...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.surfaceVariant).background(MaterialTheme.colorScheme.surface)
        ) {
            items(commands) { cmd ->
                val timeStr = dateFormat.format(Date(cmd.timestamp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "[$timeStr]",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = cmd.action,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Divider(color = MaterialTheme.colorScheme.background)
            }
        }
    }
}
