package com.nexa.mobilebridge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nexa.mobilebridge.NexaMainTab
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.data.chat.NexaAvatarState
import com.nexa.mobilebridge.theme.LocalNexaColors
import kotlinx.coroutines.delay

/**
 * 38 dp N.E.X.A Robot Visor with two horizontal OLED capsule eyes (#64FFDA in Dark).
 * Pure eyes only (no mouth) with horizontal aspect ratio so it never resembles a Pause icon.
 * Implements PRD 12.2 / FR-CORE-05 / FR-CHAT-13:
 * - Responds to IDLE, LISTENING, THINKING, STREAMING, ALERT
 * - Blink/pulse loop runs ONLY while Lifecycle >= STARTED (zero frame callbacks when screen off)
 */
@Composable
fun NexaVisorBadge(
    eyeColor: Color,
    avatarState: NexaAvatarState = NexaAvatarState.IDLE,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isBlinking by remember { mutableStateOf(false) }
    var pulsePhase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(lifecycleOwner, avatarState) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (avatarState) {
                NexaAvatarState.IDLE, NexaAvatarState.LISTENING -> {
                    pulsePhase = 0f
                    while (true) {
                        delay(4_200L)
                        isBlinking = true
                        delay(110L)
                        isBlinking = false
                    }
                }
                NexaAvatarState.THINKING, NexaAvatarState.STREAMING -> {
                    isBlinking = false
                    while (true) {
                        pulsePhase = if (pulsePhase == 0f) 1f else 0f
                        delay(420L)
                    }
                }
                NexaAvatarState.ALERT -> {
                    isBlinking = false
                    pulsePhase = 0f
                }
            }
        }
    }

    val effectiveEyeColor = when (avatarState) {
        NexaAvatarState.THINKING -> nexaColors.emerald
        NexaAvatarState.STREAMING -> MaterialTheme.colorScheme.primary
        NexaAvatarState.ALERT -> nexaColors.amber
        NexaAvatarState.IDLE, NexaAvatarState.LISTENING -> eyeColor
    }

    Surface(
        color = if (nexaColors.isDark) Color(0xFF0D141C) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (avatarState == NexaAvatarState.ALERT) nexaColors.amber.copy(alpha = 0.7f) else nexaColors.cardBorder
        ),
        modifier = modifier.size(width = 48.dp, height = 38.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baseEyeWidth = size.width * 0.25f
            val baseEyeHeight = when {
                isBlinking -> size.height * 0.05f
                avatarState == NexaAvatarState.LISTENING -> size.height * 0.22f
                avatarState == NexaAvatarState.THINKING -> size.height * 0.12f
                avatarState == NexaAvatarState.STREAMING && pulsePhase > 0.5f -> size.height * 0.21f
                else -> size.height * 0.17f
            }
            val rightEyeHeight = if (avatarState == NexaAvatarState.THINKING && pulsePhase > 0.5f) {
                size.height * 0.18f
            } else {
                baseEyeHeight
            }

            val gap = size.width * 0.14f
            val totalWidth = (baseEyeWidth * 2f) + gap
            val horizontalShift = when (avatarState) {
                NexaAvatarState.LISTENING -> size.width * 0.03f
                NexaAvatarState.THINKING -> if (pulsePhase > 0.5f) size.width * 0.02f else -size.width * 0.02f
                else -> 0f
            }
            val startX = ((size.width - totalWidth) / 2f) + horizontalShift
            val verticalShift = if (avatarState == NexaAvatarState.LISTENING) {
                size.height * 0.08f
            } else {
                0f
            }
            val leftTopY = ((size.height - baseEyeHeight) / 2f) + verticalShift
            val rightTopY = ((size.height - rightEyeHeight) / 2f) + verticalShift

            drawRoundRect(
                color = effectiveEyeColor,
                topLeft = Offset(startX, leftTopY),
                size = Size(baseEyeWidth, baseEyeHeight),
                cornerRadius = CornerRadius(baseEyeHeight / 2f, baseEyeHeight / 2f)
            )
            drawRoundRect(
                color = effectiveEyeColor,
                topLeft = Offset(startX + baseEyeWidth + gap, rightTopY),
                size = Size(baseEyeWidth, rightEyeHeight),
                cornerRadius = CornerRadius(rightEyeHeight / 2f, rightEyeHeight / 2f)
            )
        }
    }
}

/**
 * Uniform Top App Bar matching Stitch Blueprint v2 (01_chat_ai_native_v2 & 02_hud_bridge_control_v2).
 * Shows 38 dp Visor with OLED eyes, "N.E.X.A" + icon & text connection status, optional Voice Call quick action, and 48 dp Settings button.
 */
@Composable
fun NexaTopBar(
    activeTab: NexaMainTab,
    connectionState: ConnectionState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    avatarState: NexaAvatarState = NexaAvatarState.IDLE,
    onStartVoiceCall: (() -> Unit)? = null
) {
    val nexaColors = LocalNexaColors.current
    val (statusColor: Color, statusIcon: ImageVector, statusText: String) = when (connectionState) {
        is ConnectionState.Connected -> Triple(
            nexaColors.emerald,
            Icons.Default.CheckCircle,
            if (activeTab == NexaMainTab.CHAT) "Terhubung (TLS 1.3)" else "Terhubung · TLS"
        )
        is ConnectionState.Connecting -> Triple(nexaColors.amber, Icons.Default.Sync, "Menyambungkan...")
        is ConnectionState.Reconnecting -> Triple(nexaColors.amber, Icons.Default.Sync, "Mencoba ulang #${connectionState.attempt}")
        is ConnectionState.Disconnected -> Triple(nexaColors.crimson, Icons.Default.CloudOff, "Terputus")
        is ConnectionState.AuthFailed -> Triple(nexaColors.crimson, Icons.Default.ErrorOutline, "Gagal Otentikasi")
    }

    val eyeTint = when (connectionState) {
        is ConnectionState.Connected -> nexaColors.visorEye
        is ConnectionState.Connecting, is ConnectionState.Reconnecting -> nexaColors.amber
        is ConnectionState.Disconnected, is ConnectionState.AuthFailed -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val subtitleLabel = when {
        activeTab == NexaMainTab.CHAT && avatarState == NexaAvatarState.THINKING -> "Sedang menganalisis..."
        activeTab == NexaMainTab.CHAT && avatarState == NexaAvatarState.STREAMING -> "Sedang mengetik balasan..."
        activeTab == NexaMainTab.CHAT && avatarState == NexaAvatarState.LISTENING -> "Mendengarkan..."
        activeTab == NexaMainTab.CHAT -> statusText
        else -> "$statusText · ${activeTab.label.uppercase()}"
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(62.dp)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NexaVisorBadge(
                        eyeColor = eyeTint,
                        avatarState = avatarState
                    )

                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = "N.E.X.A",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = statusText,
                                tint = statusColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = subtitleLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onStartVoiceCall != null && (activeTab == NexaMainTab.CHAT || activeTab == NexaMainTab.HUD)) {
                        IconButton(
                            onClick = onStartVoiceCall,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Panggilan Suara Langsung N.E.X.A",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Pengaturan dan Keamanan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = nexaColors.cardBorder.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Uniform 4-Tab Bottom Navigation Bar (CHAT | HUD | LOGS | SHELL) with consistent 2dp top indicator bar
 * and >= 48dp touch targets (PRD 12.1 / Stitch Blueprint v2).
 */
@Composable
fun NexaBottomNavBar(
    activeTab: NexaMainTab,
    onSelectTab: (NexaMainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(
                thickness = 1.dp,
                color = nexaColors.cardBorder
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NexaMainTab.entries.forEach { tab ->
                    val isSelected = tab == activeTab
                    val activeTint = MaterialTheme.colorScheme.primary
                    val inactiveTint = MaterialTheme.colorScheme.onSurfaceVariant
                    val tint = if (isSelected) activeTint else inactiveTint

                    val icon = when (tab) {
                        NexaMainTab.CHAT -> if (isSelected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
                        NexaMainTab.HUD -> if (isSelected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
                        NexaMainTab.LOGS -> if (isSelected) Icons.AutoMirrored.Filled.ReceiptLong else Icons.AutoMirrored.Outlined.ReceiptLong
                        NexaMainTab.SHELL -> if (isSelected) Icons.Filled.Terminal else Icons.Outlined.Terminal
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .semantics {
                                selected = isSelected
                                contentDescription = tab.label
                            }
                            .clickable(
                                role = Role.Tab,
                                onClick = { onSelectTab(tab) }
                            ),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .width(34.dp)
                                .height(2.dp)
                                .background(
                                    color = if (isSelected) activeTint else Color.Transparent,
                                    shape = RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)
                                )
                        )

                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(top = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = tab.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = tint
                            )
                        }
                    }
                }
            }
        }
    }
}
