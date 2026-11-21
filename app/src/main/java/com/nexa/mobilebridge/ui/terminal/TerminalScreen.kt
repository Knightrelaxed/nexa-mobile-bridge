package com.nexa.mobilebridge.ui.terminal

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nexa.mobilebridge.core.security.SecureScreenEffect
import com.nexa.mobilebridge.core.security.findActivity
import com.nexa.mobilebridge.data.chat.NexaAvatarState
import com.nexa.mobilebridge.data.terminal.TerminalCell
import com.nexa.mobilebridge.data.terminal.TerminalScreenSnapshot
import com.nexa.mobilebridge.data.terminal.TerminalSessionState
import com.nexa.mobilebridge.data.terminal.TerminalSessionStatus
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import com.nexa.mobilebridge.ui.components.NexaVisorBadge
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Remote Inspection Terminal Screen matching Stitch Blueprint v2 (04_terminal_pty_v2.png)
 * and PRD FR-TERM-01..18, FR-SEC-06, §12.1, §12.3:
 * - Single compact 40 dp header (nexa-term@vps + status dot + "Inspeksi" + lock + menu ⋮)
 * - Ref-counted FLAG_SECURE via SecureScreenEffect()
 * - Pure-Kotlin VT100/xterm-256color ANSI Canvas (0 external .so -> 100% 16 KB aligned)
 * - Debounced 150 ms RESIZE (SIGWINCH) on keyboard / rotation / font size change
 * - Hidden TerminalInputSink (KeyboardType.Password verified on Galaxy A33)
 * - 2-Row 44x48 dp Accessory Bar with shared ModifierState & arrow key-repeat
 */
@Composable
fun TerminalScreen(
    onOpenSettings: () -> Unit,
    isLandscape: Boolean = false,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    // Otomatis aktif FLAG_SECURE pada build release; tetap bebas screenshot saat debug/testing.
    SecureScreenEffect(allowInDebugBuild = true)

    val context = LocalContext.current
    val view = LocalView.current
    val nexaColors = LocalNexaColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val emulatorVersion by viewModel.emulatorVersion.collectAsStateWithLifecycle()
    val fontSizeSp by viewModel.fontSizeSp.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticEnabled.collectAsStateWithLifecycle()
    val enrollmentStatus by viewModel.enrollmentStatus.collectAsStateWithLifecycle()
    val isRow2Expanded by viewModel.isRow2Expanded.collectAsStateWithLifecycle()

    val inputFocusRequester = remember { FocusRequester() }
    var scrollOffsetLines by remember { mutableIntStateOf(0) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var keepAliveForSettings by remember { mutableStateOf(false) }

    // FR-TERM-13 / PRD §13: Screen off -> pause output (CREDIT=0), do not kill bash
    LifecycleStartEffect(Unit) {
        keepAliveForSettings = false
        viewModel.refreshPreferences()
        viewModel.onScreenResumed()
        onStopOrDispose {
            viewModel.onScreenPaused()
        }
    }

    // PRD §13: Distinguish deliberate Terminal tab switch from screen off, rotation, or Settings navigation
    DisposableEffect(Unit) {
        onDispose {
            val activity = context.findActivity()
            val isRotating = activity?.isChangingConfigurations == true
            val isStillForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (isStillForeground && !isRotating && !keepAliveForSettings) {
                viewModel.onTabClosed()
            }
        }
    }

    fun triggerHaptic() {
        if (hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    val snapshot = remember(emulatorVersion, scrollOffsetLines) {
        viewModel.emulator.snapshot(scrollOffsetLines)
    }

    LaunchedEffect(snapshot.scrollbackLines) {
        if (scrollOffsetLines > snapshot.scrollbackLines) {
            scrollOffsetLines = snapshot.scrollbackLines
        }
    }

    val openSettingsPreservingSession: () -> Unit = {
        keepAliveForSettings = true
        onOpenSettings()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // 1. Single Compact 40 dp Header matching 04_terminal_pty_v2.png (No double top bar!)
        TerminalSingleCompactHeader(
            sessionState = sessionState,
            fontSizeSp = fontSizeSp,
            isLandscape = isLandscape,
            onAdjustFont = { delta ->
                triggerHaptic()
                viewModel.adjustFontSize(delta)
            },
            onOpenSettings = openSettingsPreservingSession,
            onRequestDisconnect = { showDisconnectConfirm = true }
        )

        // 2. Detached / Reconnect Grace Countdown Banner (localized 1 Hz ticker ONLY when detached)
        if (sessionState.status == TerminalSessionStatus.DETACHED_RECONNECTING) {
            DetachedReconnectBanner(
                detachGraceEndsAtMs = sessionState.detachGraceEndsAtMs,
                onResume = {
                    triggerHaptic()
                    val activity = context.findActivity() as? FragmentActivity
                    viewModel.startOrResumeSession(activity, forceNew = false)
                }
            )
        }

        // 3. Main Terminal Viewport Canvas (~22-24 rows portrait with keyboard, ~100+ cols landscape)
        Surface(
            color = Color(0xFF06090F),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, nexaColors.cardBorder),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Hidden Galaxy-A33-verified IME input sink mounted ONLY while session is ACTIVE (FR-TERM-06 / U-M7)
                if (sessionState.status == TerminalSessionStatus.ACTIVE) {
                    TerminalInputSink(
                        modifiers = viewModel.modifierState,
                        focusRequester = inputFocusRequester,
                        onSendBytes = { bytes ->
                            scrollOffsetLines = 0
                            viewModel.sendInputBytes(bytes)
                        }
                    )
                }

                when (sessionState.status) {
                    TerminalSessionStatus.ACTIVE,
                    TerminalSessionStatus.PAUSED_SCREEN_OFF,
                    TerminalSessionStatus.DETACHED_RECONNECTING -> {
                        TerminalAnsiCanvas(
                            snapshot = snapshot,
                            fontSizeSp = fontSizeSp,
                            isSessionActive = sessionState.status == TerminalSessionStatus.ACTIVE,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            onViewportSizeChanged = { rows, cols ->
                                viewModel.onViewportMeasured(rows, cols)
                            },
                            onScrollLinesDelta = { delta ->
                                scrollOffsetLines = (scrollOffsetLines + delta).coerceIn(0, snapshot.scrollbackLines)
                            },
                            onTapTerminal = {
                                if (sessionState.status == TerminalSessionStatus.ACTIVE) {
                                    runCatching { inputFocusRequester.requestFocus() }
                                    keyboardController?.show()
                                }
                            }
                        )
                    }

                    TerminalSessionStatus.CONNECTING,
                    TerminalSessionStatus.AWAITING_BIOMETRIC -> {
                        TerminalStateOverlay(
                            isLoading = true,
                            isKillSwitchOff = false,
                            title = if (sessionState.status == TerminalSessionStatus.AWAITING_BIOMETRIC) {
                                "Verifikasi Sidik Jari (scope=term)"
                            } else {
                                "Menyambungkan PTY /io/term..."
                            },
                            subtitle = "Menyiapkan shell inspeksi terisolasi (user nexa-term, tanpa sudo, batas sesi 4 jam).",
                            actionLabel = null,
                            onAction = {}
                        )
                    }

                    TerminalSessionStatus.DISABLED_BY_SERVER -> {
                        TerminalStateOverlay(
                            isLoading = false,
                            isKillSwitchOff = true,
                            title = "Terminal dinonaktifkan di server",
                            subtitle = "Sakelar keamanan server-side (kill switch) dalam posisi NONAKTIF (FR-TERM-18). Aktifkan secara eksplisit di VPS hanya saat dibutuhkan.",
                            actionLabel = "Cek Ulang Status Server",
                            actionIcon = Icons.Default.Refresh,
                            onAction = {
                                triggerHaptic()
                                val activity = context.findActivity() as? FragmentActivity
                                viewModel.startOrResumeSession(activity, forceNew = true)
                            }
                        )
                    }

                    TerminalSessionStatus.DISCONNECTED,
                    TerminalSessionStatus.ERROR -> {
                        val needsEnroll = enrollmentStatus != "ACTIVE"
                        val errMsg = sessionState.errorMessage
                        TerminalStateOverlay(
                            isLoading = false,
                            isKillSwitchOff = false,
                            title = when {
                                needsEnroll -> "Perangkat Belum Terdaftar"
                                sessionState.status == TerminalSessionStatus.ERROR -> "Koneksi Terminal Gagal"
                                else -> "Terminal Inspeksi Siaga (On-Demand)"
                            },
                            subtitle = when {
                                !errMsg.isNullOrBlank() -> errMsg
                                needsEnroll -> "Perangkat belum terdaftar untuk Terminal Inspeksi. Buka Pengaturan & Keamanan lalu daftarkan kunci perangkat dengan CLI nexa-enroll."
                                else -> "Shell /bin/bash -l terisolasi (nexa-term) hanya dijalankan saat dibuka dengan sidik jari. Layar mati menjeda output (masa resume 120 dtk)."
                            },
                            actionLabel = if (needsEnroll) {
                                "Buka Pengaturan & Enroll"
                            } else {
                                "Buka Sesi Terminal (Sidik Jari)"
                            },
                            actionIcon = if (needsEnroll) {
                                Icons.Default.Settings
                            } else {
                                Icons.Default.Fingerprint
                            },
                            onAction = {
                                triggerHaptic()
                                if (needsEnroll) {
                                    openSettingsPreservingSession()
                                } else {
                                    val activity = context.findActivity() as? FragmentActivity
                                    viewModel.startOrResumeSession(activity, forceNew = true)
                                }
                            }
                        )
                    }
                }
            }
        }

        // 4. Compact Footer matching 04_terminal_pty_v2.png (ellipsis guard for large font scale)
        Text(
            text = "xterm-256color • UTF-8 • Scrollback: ${snapshot.scrollbackLines}/1.000",
            style = NexaMonoTypography.codeLine.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )

        // 5. 2-Row Ergonomic Accessory Bar (subtly dimmed while session is not ACTIVE, RD-L1)
        val isTerminalActive = sessionState.status == TerminalSessionStatus.ACTIVE
        TerminalAccessoryBar(
            modifiers = viewModel.modifierState,
            isRow2Expanded = isRow2Expanded,
            onToggleRow2 = { viewModel.toggleRow2Expanded() },
            onSendBytes = { bytes ->
                if (isTerminalActive) {
                    scrollOffsetLines = 0
                    viewModel.sendInputBytes(bytes)
                }
            },
            onHapticTick = { triggerHaptic() },
            modifier = Modifier
                .padding(horizontal = 2.dp, vertical = 4.dp)
                .alpha(if (isTerminalActive) 1f else 0.55f)
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Putuskan Sesi Terminal?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Menutup sesi akan mengirim SIGHUP/SIGKILL ke seluruh process group bash di server agar tidak meninggalkan proses zombie.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirm = false
                        triggerHaptic()
                        viewModel.disconnectSession()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nexaColors.crimson,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Putuskan Sesi", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun DetachedReconnectBanner(
    detachGraceEndsAtMs: Long,
    onResume: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lifecycleOwner, detachGraceEndsAtMs) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    val graceSecLeft = max(0L, (detachGraceEndsAtMs - nowMs) / 1000L)
    Surface(
        color = if (nexaColors.isDark) Color(0xFF241A0E) else Color(0xFFFFF3E0),
        border = BorderStroke(1.dp, nexaColors.amber.copy(alpha = 0.8f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Menyambung kembali • Sisa masa resume: $graceSecLeft dtk",
                style = NexaMonoTypography.codeLine,
                fontWeight = FontWeight.SemiBold,
                color = nexaColors.amber,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onResume) {
                Text(
                    text = "Pulihkan",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Single compact 40 dp header matching 04_terminal_pty_v2.png:
 * [Visor] nexa-term@vps •   [Inspeksi] [🔒] [⋮]
 */
@Composable
private fun TerminalSingleCompactHeader(
    sessionState: TerminalSessionState,
    fontSizeSp: Int,
    isLandscape: Boolean,
    onAdjustFont: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestDisconnect: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    var menuExpanded by remember { mutableStateOf(false) }
    var menuOpenedAtMs by remember { mutableLongStateOf(0L) }

    val statusDotColor = when (sessionState.status) {
        TerminalSessionStatus.ACTIVE -> nexaColors.emerald
        TerminalSessionStatus.PAUSED_SCREEN_OFF,
        TerminalSessionStatus.DETACHED_RECONNECTING,
        TerminalSessionStatus.CONNECTING,
        TerminalSessionStatus.AWAITING_BIOMETRIC -> nexaColors.amber
        TerminalSessionStatus.DISABLED_BY_SERVER,
        TerminalSessionStatus.ERROR -> nexaColors.crimson
        TerminalSessionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val remainingSessionText = remember(sessionState.sessionExpiresAtMs, menuOpenedAtMs, sessionState.activeSid) {
        val now = if (menuOpenedAtMs > 0L) menuOpenedAtMs else System.currentTimeMillis()
        if (sessionState.sessionExpiresAtMs <= now || sessionState.activeSid == null) {
            "Belum aktif"
        } else {
            val totalMin = (sessionState.sessionExpiresAtMs - now) / 60_000L
            val hours = totalMin / 60L
            val mins = totalMin % 60L
            "Sisa ${hours}j ${mins}m"
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!isLandscape) Modifier.statusBarsPadding() else Modifier)
                    .height(40.dp)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NexaVisorBadge(
                        eyeColor = nexaColors.visorEye,
                        avatarState = if (sessionState.status == TerminalSessionStatus.ACTIVE) {
                            NexaAvatarState.IDLE
                        } else {
                            NexaAvatarState.ALERT
                        },
                        modifier = Modifier.size(width = 38.dp, height = 28.dp)
                    )
                    Text(
                        text = "nexa-term@vps",
                        style = NexaMonoTypography.metricMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusDotColor, CircleShape)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = if (nexaColors.isDark) Color(0xFF0A261C) else Color(0xFFD9F5E5),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, nexaColors.emerald.copy(alpha = 0.7f))
                    ) {
                        Text(
                            text = "Inspeksi",
                            style = NexaMonoTypography.badge.copy(fontSize = 11.sp),
                            color = nexaColors.emerald,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = "Mode testing (screenshot aktif)",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )

                    Box {
                        IconButton(
                            onClick = {
                                menuOpenedAtMs = System.currentTimeMillis()
                                menuExpanded = true
                            },
                            modifier = Modifier.requiredSize(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu sesi terminal",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Durasi Sesi (Maks 4j): $remainingSessionText",
                                        style = NexaMonoTypography.codeLine
                                    )
                                },
                                onClick = { menuExpanded = false },
                                enabled = false
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Perkecil Font ($fontSizeSp sp -> ${(fontSizeSp - 1).coerceAtLeast(8)} sp)") },
                                onClick = {
                                    onAdjustFont(-1)
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Perbesar Font ($fontSizeSp sp -> ${(fontSizeSp + 1).coerceAtMost(16)} sp)") },
                                onClick = {
                                    onAdjustFont(1)
                                    menuExpanded = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Pengaturan & Keamanan") },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    menuExpanded = false
                                    onOpenSettings()
                                }
                            )
                            if (sessionState.activeSid != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Putuskan Sesi",
                                            color = nexaColors.crimson,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PowerSettingsNew,
                                            contentDescription = null,
                                            tint = nexaColors.crimson,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onRequestDisconnect()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun TerminalAnsiCanvas(
    snapshot: TerminalScreenSnapshot,
    fontSizeSp: Int,
    isSessionActive: Boolean,
    cursorColor: Color,
    onViewportSizeChanged: (rows: Int, cols: Int) -> Unit,
    onScrollLinesDelta: (Int) -> Unit,
    onTapTerminal: () -> Unit
) {
    // U-C1: Sized TextMeasurer cache (64 >= typical 24-48 terminal rows) to prevent LRU thrashing
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val baseStyle = remember(fontSizeSp) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 4).sp,
            color = Color(TerminalCell.DEFAULT_FG)
        )
    }

    val cellMetrics = remember(baseStyle) {
        val m = textMeasurer.measure("M", style = baseStyle)
        Pair(max(1f, m.size.width.toFloat()), max(1f, m.size.height.toFloat()))
    }

    // U-C1: Memoize all row TextLayoutResults OUTSIDE Canvas draw phase (only updates when snapshot/font changes)
    val rowLayouts: List<TextLayoutResult> = remember(snapshot, baseStyle) {
        snapshot.grid.map { rowCells ->
            val rowAnnotated = buildRowAnnotatedString(rowCells)
            textMeasurer.measure(
                text = rowAnnotated,
                style = baseStyle,
                maxLines = 1,
                softWrap = false
            )
        }
    }

    // U-C3: Always read live scroll callback and row height inside pointerInput(Unit)
    val latestOnScrollLinesDelta by rememberUpdatedState(onScrollLinesDelta)
    val latestRowHeightPx by rememberUpdatedState(cellMetrics.second)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable { onTapTerminal() }
            .pointerInput(Unit) {
                var accumY = 0f
                detectVerticalDragGestures(
                    onDragEnd = { accumY = 0f },
                    onDragCancel = { accumY = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        accumY += dragAmount
                        val rowHeightPx = latestRowHeightPx
                        if (accumY >= rowHeightPx) {
                            val lines = (accumY / rowHeightPx).toInt()
                            latestOnScrollLinesDelta(lines)
                            accumY -= lines * rowHeightPx
                        } else if (accumY <= -rowHeightPx) {
                            val lines = (accumY / rowHeightPx).toInt()
                            latestOnScrollLinesDelta(lines)
                            accumY -= lines * rowHeightPx
                        }
                    }
                )
            }
    ) {
        val cellWidthPx = cellMetrics.first
        val cellHeightPx = cellMetrics.second
        val calcCols = (constraints.maxWidth / cellWidthPx).toInt().coerceIn(20, 240)
        val calcRows = (constraints.maxHeight / cellHeightPx).toInt().coerceIn(8, 120)

        LaunchedEffect(calcRows, calcCols) {
            onViewportSizeChanged(calcRows, calcCols)
        }

        // Main ANSI text + cell background Canvas (never invalidated by cursor blink!)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val grid = snapshot.grid
            for (r in grid.indices) {
                val rowCells = grid[r]
                val yTop = r * cellHeightPx
                if (yTop > size.height) break

                // 1. Draw non-transparent cell backgrounds
                for (c in rowCells.indices) {
                    val cell = rowCells[c]
                    if (cell.bgArgb != TerminalCell.DEFAULT_BG) {
                        drawRect(
                            color = Color(cell.bgArgb),
                            topLeft = Offset(c * cellWidthPx, yTop),
                            size = Size(cellWidthPx + 0.5f, cellHeightPx)
                        )
                    }
                }

                // 2. Draw pre-measured TextLayoutResult
                rowLayouts.getOrNull(r)?.let { layoutResult ->
                    drawText(
                        textLayoutResult = layoutResult,
                        topLeft = Offset(0f, yTop)
                    )
                }
            }
        }

        // U-C2: Dedicated lightweight 1 Hz blinking cursor overlay (runs ONLY while ACTIVE + cursorVisible)
        BlinkingTerminalCursorOverlay(
            isActive = isSessionActive,
            cursorVisible = snapshot.cursorVisible,
            cursorRow = snapshot.cursorRow,
            cursorCol = snapshot.cursorCol,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
            cursorColor = cursorColor
        )
    }
}

private fun buildRowAnnotatedString(rowCells: List<TerminalCell>): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < rowCells.size) {
            val first = rowCells[i]
            val runStart = i
            while (i < rowCells.size &&
                rowCells[i].fgArgb == first.fgArgb &&
                rowCells[i].bold == first.bold &&
                rowCells[i].italic == first.italic &&
                rowCells[i].underline == first.underline
            ) {
                i++
            }
            val sb = StringBuilder(i - runStart)
            for (k in runStart until i) {
                sb.append(rowCells[k].ch)
            }
            withStyle(
                SpanStyle(
                    color = Color(first.fgArgb),
                    fontWeight = if (first.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (first.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (first.underline) TextDecoration.Underline else TextDecoration.None
                )
            ) {
                append(sb.toString())
            }
        }
    }
}

@Composable
private fun BlinkingTerminalCursorOverlay(
    isActive: Boolean,
    cursorVisible: Boolean,
    cursorRow: Int,
    cursorCol: Int,
    cellWidthPx: Float,
    cellHeightPx: Float,
    cursorColor: Color
) {
    if (!isActive || !cursorVisible) return

    val lifecycleOwner = LocalLifecycleOwner.current
    var blinkOn by remember { mutableStateOf(true) }

    LaunchedEffect(lifecycleOwner, cursorRow, cursorCol) {
        blinkOn = true
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(500L)
                blinkOn = !blinkOn
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (!blinkOn) return@Canvas
        val cx = cursorCol * cellWidthPx
        val cy = cursorRow * cellHeightPx
        if (cx < size.width && cy < size.height) {
            drawRect(
                color = cursorColor.copy(alpha = 0.85f),
                topLeft = Offset(cx, cy),
                size = Size(cellWidthPx, cellHeightPx)
            )
        }
    }
}

@Composable
private fun TerminalStateOverlay(
    isLoading: Boolean,
    isKillSwitchOff: Boolean,
    title: String,
    subtitle: String,
    actionLabel: String?,
    actionIcon: ImageVector = Icons.Default.Fingerprint,
    onAction: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Icon(
                    imageVector = when {
                        isKillSwitchOff -> Icons.Default.Block
                        else -> Icons.Default.Terminal
                    },
                    contentDescription = null,
                    tint = if (isKillSwitchOff) nexaColors.crimson else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9FB0C7)
            )

            if (actionLabel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nexaColors.primaryButtonFill,
                        contentColor = nexaColors.onPrimaryButtonFill
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
