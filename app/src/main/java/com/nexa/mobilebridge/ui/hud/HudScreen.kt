package com.nexa.mobilebridge.ui.hud

import android.content.Intent
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobilebridge.CommandHistoryRecord
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HUD / Bridge Control screen matching Stitch Blueprint v2 (02_hud_bridge_control_v2.png).
 */
@Composable
fun HudScreen(
    viewModel: HudViewModel = hiltViewModel(),
    onToggleService: (Boolean) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showStopConfirmDialog by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    when (val state = uiState) {
        is HudUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        is HudUiState.Active -> {
            val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Dual-WS Side-by-Side Cards (Koneksi A | Koneksi B)
                item(key = "dual_ws_cards") {
                    DualConnectionRow(
                        connectionState = state.connectionState,
                        serverHost = state.serverHost,
                        networkType = state.lastTelemetry?.network_type,
                        isSidecarActive = state.isSidecarActive,
                        sidecarStatusLabel = state.sidecarStatusLabel
                    )
                }

                if (state.connectionState !is ConnectionState.Connected && state.isServiceRunning) {
                    item(key = "reconnect_action") {
                        OutlinedButton(
                            onClick = { viewModel.triggerReconnect() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sambungkan Ulang WebSocket")
                        }
                    }
                }

                // 2. Hardware & Power Telemetry 2x2 Grid (Real Galaxy A33 values)
                item(key = "telemetry_grid") {
                    HudTelemetryGrid(state = state)
                }

                // 3. Banking-Safe Hygiene & Permissions Strip
                item(key = "hygiene_strip") {
                    PermissionsHygieneRow(
                        permissions = state.permissions,
                        onOpenUsageSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        onOpenNotifSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )
                }

                // 4. Single Framed Card: RIWAYAT PERINTAH PERANGKAT
                item(key = "command_history_card") {
                    CommandHistoryCard(
                        commands = state.recentCommands,
                        timeFormat = timeFormat
                    )
                }

                // 6. Destructive Guard Button: Hold 2s to Stop / Tap to Start Bridge Service
                item(key = "service_control") {
                    Spacer(modifier = Modifier.height(2.dp))
                    BridgeServiceControlButton(
                        isRunning = state.isServiceRunning,
                        onStart = { onToggleService(true) },
                        onStopConfirmedByHold = { onToggleService(false) },
                        onRequestStopDialog = { showStopConfirmDialog = true }
                    )
                }
            }
        }
    }

    if (showStopConfirmDialog) {
        val nexaColors = LocalNexaColors.current
        AlertDialog(
            onDismissRequest = { showStopConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Hentikan Layanan Bridge?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Koneksi WebSocket inti (/ws), telemetri perangkat, dan kendali jarak jauh akan dihentikan hingga Anda menyalakannya kembali.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmDialog = false
                        onToggleService(false)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nexaColors.crimson,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Ya, Hentikan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun DualConnectionRow(
    connectionState: ConnectionState,
    serverHost: String,
    networkType: String?,
    isSidecarActive: Boolean,
    sidecarStatusLabel: String
) {
    val nexaColors = LocalNexaColors.current
    val (wsAColor, wsAIcon, wsAStatus) = when (connectionState) {
        is ConnectionState.Connected -> Triple(nexaColors.emerald, Icons.Default.CheckCircle, "Terhubung · TLS")
        is ConnectionState.Connecting -> Triple(nexaColors.amber, Icons.Default.Sync, "Menyambungkan")
        is ConnectionState.Reconnecting -> Triple(nexaColors.amber, Icons.Default.Sync, "Ulang #${connectionState.attempt}")
        is ConnectionState.Disconnected -> Triple(nexaColors.crimson, Icons.Default.CloudOff, "Terputus")
        is ConnectionState.AuthFailed -> Triple(nexaColors.crimson, Icons.Default.ErrorOutline, "Gagal Auth")
    }

    val netBadge = when (networkType) {
        "WIFI" -> "Wi-Fi"
        "CELLULAR" -> "Seluler"
        "ETHERNET" -> "Ethernet"
        "OFFLINE" -> "Offline"
        else -> null
    }
    val displayHost = remember(serverHost) {
        val raw = serverHost.ifBlank { "Belum diatur" }
        if (raw.length > 26 && raw.contains(":")) {
            val portSuffix = raw.substringAfterLast(":")
            val hostOnly = raw.substringBeforeLast(":")
            val prefix = hostOnly.take(11)
            val tail = hostOnly.takeLast(9)
            "$prefix...$tail:$portSuffix"
        } else {
            raw
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Koneksi A (Inti /ws)
        HudSurfaceCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Koneksi A (/ws)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = wsAIcon,
                    contentDescription = wsAStatus,
                    tint = wsAColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = wsAStatus,
                style = NexaMonoTypography.badge,
                color = wsAColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = displayHost,
                style = NexaMonoTypography.codeLine.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (netBadge != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Via $netBadge",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        // Koneksi B (Sidecar /io) - reflects live SidecarLogsClient / SidecarTerminalClient state (U-H11 / RD-H2)
        HudSurfaceCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Koneksi B (/io)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSidecarActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isSidecarActive) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = sidecarStatusLabel,
                    tint = if (isSidecarActive) nexaColors.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = sidecarStatusLabel,
                style = if (isSidecarActive) NexaMonoTypography.badge else MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isSidecarActive) nexaColors.emerald else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Aktif saat buka Log / Shell",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HudTelemetryGrid(state: HudUiState.Active) {
    val nexaColors = LocalNexaColors.current
    val telemetry = state.lastTelemetry

    val batteryValue = if (telemetry != null) {
        val modeText = if (telemetry.battery_charging) "Mengisi" else "Normal"
        "${telemetry.battery_level}% · $modeText"
    } else {
        "Menunggu data"
    }
    val batteryIcon = if (telemetry?.battery_charging == true) {
        Icons.Default.BatteryChargingFull
    } else {
        Icons.Default.BatteryStd
    }

    val screenValue = when (telemetry?.screen_on) {
        true -> "Menyala (${state.displayRefreshRateHz} Hz)"
        false -> "Mati"
        null -> "Menyala (${state.displayRefreshRateHz} Hz)"
    }

    val wakeLockValue = if (state.isPowerLockActive) {
        "Aktif (Transfer)"
    } else {
        "Dilepas (Hemat Daya)"
    }
    val wakeLockIcon = if (state.isPowerLockActive) Icons.Default.Bolt else Icons.Default.CheckCircle
    val wakeLockAccent = if (state.isPowerLockActive) nexaColors.amber else nexaColors.emerald

    val hasGeofence = !state.geofenceSummary.startsWith("Belum")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryStatCard(
                label = "Baterai",
                value = batteryValue,
                icon = batteryIcon,
                iconTint = nexaColors.emerald,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            TelemetryStatCard(
                label = "Status Layar",
                value = screenValue,
                icon = Icons.Default.Smartphone,
                iconTint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryStatCard(
                label = "WakeLock CPU",
                value = wakeLockValue,
                icon = wakeLockIcon,
                iconTint = wakeLockAccent,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            TelemetryStatCard(
                label = "Geofence",
                value = state.geofenceSummary,
                icon = Icons.Default.LocationOn,
                iconTint = if (hasGeofence) nexaColors.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun TelemetryStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    HudSurfaceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PermissionsHygieneRow(
    permissions: PermissionSnapshot,
    onOpenUsageSettings: () -> Unit,
    onOpenNotifSettings: () -> Unit
) {
    HudSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PermissionQuickChip(
                icon = Icons.Outlined.QueryStats,
                label = if (permissions.hasUsageStats) "Usage: Aktif" else "Usage: Off",
                granted = permissions.hasUsageStats,
                onClick = onOpenUsageSettings,
                modifier = Modifier.weight(1f)
            )
            PermissionQuickChip(
                icon = if (permissions.hasNotifListener) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                label = if (permissions.hasNotifListener) "Notif: Aktif" else "Notif: Off",
                granted = permissions.hasNotifListener,
                onClick = onOpenNotifSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PermissionQuickChip(
    icon: ImageVector,
    label: String,
    granted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val accent = if (granted) nexaColors.emerald else nexaColors.amber
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1
        )
    }
}

@Composable
private fun CommandHistoryCard(
    commands: List<CommandHistoryRecord>,
    timeFormat: SimpleDateFormat
) {
    val nexaColors = LocalNexaColors.current
    HudSurfaceCard {
        Text(
            text = "RIWAYAT PERINTAH PERANGKAT",
            style = NexaMonoTypography.badge,
            color = MaterialTheme.colorScheme.onSurface
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = nexaColors.cardBorder.copy(alpha = 0.7f)
        )

        if (commands.isEmpty()) {
            Text(
                text = "Belum ada perintah perangkat yang dieksekusi pada sesi ini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                commands.take(8).forEach { item ->
                    val statusColor = if (item.isSuccess) nexaColors.emerald else nexaColors.crimson
                    val actionColor = if (item.isSensitive) nexaColors.violet else MaterialTheme.colorScheme.primary
                    Surface(
                        color = MaterialTheme.colorScheme.background.copy(alpha = if (nexaColors.isDark) 0.55f else 0.75f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = timeFormat.format(Date(item.timestamp)),
                                style = NexaMonoTypography.codeLine,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = item.action,
                                style = NexaMonoTypography.badge,
                                color = actionColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 110.dp)
                            )
                            Text(
                                text = if (item.isSuccess) "[OK]" else "[ERR]",
                                style = NexaMonoTypography.badge,
                                color = statusColor
                            )
                            Text(
                                text = "${item.durationMs}ms",
                                style = NexaMonoTypography.codeLine,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeServiceControlButton(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStopConfirmedByHold: () -> Unit,
    onRequestStopDialog: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    if (isRunning) {
        var holdProgress by remember { mutableFloatStateOf(0f) }

        // FR-CORE-07 / 02_hud_bridge_control_v2.png: 2-second hold-to-confirm with TalkBack dialog fallback
        Surface(
            color = nexaColors.crimson.copy(alpha = if (nexaColors.isDark) 0.12f else 0.08f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.2.dp, nexaColors.crimson),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics {
                    contentDescription = "Tahan 2 detik untuk hentikan layanan Bridge"
                    role = Role.Button
                    onClick(label = "Buka konfirmasi hentikan layanan Bridge") {
                        onRequestStopDialog()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        var completed = false
                        val progressJob = scope.launch {
                            val totalMs = 2_000L
                            val stepMs = 40L
                            var elapsed = 0L
                            while (elapsed < totalMs) {
                                delay(stepMs)
                                elapsed += stepMs
                                holdProgress = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                            }
                            completed = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onStopConfirmedByHold()
                        }
                        waitForUpOrCancellation()
                        progressJob.cancel()
                        if (!completed) {
                            holdProgress = 0f
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                contentAlignment = Alignment.Center
            ) {
                if (holdProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .fillMaxWidth(holdProgress)
                            .background(nexaColors.crimson.copy(alpha = 0.28f))
                    )
                }
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = nexaColors.crimson,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (holdProgress > 0f) {
                            "Menahan... ${(holdProgress * 100).toInt()}%"
                        } else {
                            "Tahan 2 dtk untuk Hentikan Layanan Bridge"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = nexaColors.crimson,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    } else {
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = nexaColors.primaryButtonFill,
                contentColor = nexaColors.onPrimaryButtonFill
            )
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Jalankan Layanan Bridge",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HudSurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, nexaColors.cardBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}
