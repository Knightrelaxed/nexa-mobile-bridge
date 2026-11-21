package com.nexa.mobilebridge.ui.logs

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobilebridge.core.security.SecureScreenEffect
import com.nexa.mobilebridge.core.security.findActivity
import com.nexa.mobilebridge.data.chat.NexaAvatarState
import com.nexa.mobilebridge.data.logs.LogBinaryProtocol
import com.nexa.mobilebridge.data.logs.LogEntry
import com.nexa.mobilebridge.data.logs.LogFilterPreset
import com.nexa.mobilebridge.data.logs.LogStreamStatus
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import com.nexa.mobilebridge.ui.components.NexaVisorBadge
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-Demand Server Log Monitor Screen matching Stitch Blueprint v2 (03_server_log_monitor_v2.png)
 * and PRD FR-LOG-01..13, FR-SEC-06, §12.1:
 * - Ref-counted FLAG_SECURE via SecureScreenEffect()
 * - LifecycleStartEffect opens /io/logs on ON_START and stops + clears buffer on ON_STOP
 * - Sub-header with status badge ("Log Aktif" / "Log Dijeda"), "Layar terlindungi",
 *   Pause/Play, Auto-Scroll, and Clear Buffer (strictly NO Export button and NO bottom regex bar)
 * - 48 dp Filter Chip Row: [✓ ALL] [FINANCE] [BRIDGE] [● ERRORS ONLY]
 * - Separate Priority badge slot ([INFO], [WARN], [ERROR]) and Category badge slot ([FINANCE], [BRIDGE], [AUTH], [SYNTHETIC])
 * - Accurate footer: "Buffer: x / 2.000 baris • Dilewati: n • Berhenti otomatis saat layar mati" + "WSS-SECURE"
 */
@Composable
fun LogViewerScreen(
    onOpenSettings: () -> Unit = {},
    viewModel: LogViewerViewModel = hiltViewModel()
) {
    // Otomatis aktif FLAG_SECURE pada build release; tetap bebas screenshot saat debug/testing.
    SecureScreenEffect(allowInDebugBuild = true)

    val context = LocalContext.current
    val view = LocalView.current
    val nexaColors = LocalNexaColors.current

    val logSnapshot by viewModel.logSnapshot.collectAsStateWithLifecycle()
    val visibleEntries = logSnapshot.visibleEntries
    val totalBufferedCount = logSnapshot.totalBufferedCount
    val skippedCount by viewModel.skippedLinesCount.collectAsStateWithLifecycle()
    val streamStatus by viewModel.streamStatus.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val activeFilter by viewModel.activeFilter.collectAsStateWithLifecycle()
    val autoScroll by viewModel.autoScrollEnabled.collectAsStateWithLifecycle()
    val enrollmentStatus by viewModel.enrollmentStatus.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticEnabled.collectAsStateWithLifecycle()

    // FR-LOG-03 / PRD §13: Start stream on ON_START; close stream & clear buffer on ON_STOP
    LifecycleStartEffect(Unit) {
        val activity = context.findActivity() as? FragmentActivity
        viewModel.onLogsTabStarted(activity)
        onStopOrDispose {
            viewModel.onLogsTabStopped()
        }
    }

    fun triggerHaptic() {
        if (hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val latestStableId = visibleEntries.lastOrNull()?.stableId ?: -1L
    var lastSeenLatestId by remember { mutableLongStateOf(-1L) }
    var hasUnseenNewLogs by remember { mutableStateOf(false) }
    var isProgrammaticJump by remember { mutableStateOf(false) }

    val latestAutoScroll by rememberUpdatedState(autoScroll)

    // Detect intentional upward drag by user (swiping finger down to read older logs)
    val scrollBreakConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f && latestAutoScroll) {
                    viewModel.setAutoScroll(false)
                }
                return Offset.Zero
            }
        }
    }

    // Re-engage auto-scroll automatically if user manually scrolls all the way down to newest log
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.canScrollForward,
                listState.layoutInfo.totalItemsCount
            )
        }.collect { (scrolling, canScrollDown, totalItems) ->
            if (!scrolling && !canScrollDown && totalItems > 0 && !isProgrammaticJump && !latestAutoScroll) {
                viewModel.setAutoScroll(true)
            }
        }
    }

    // Keep pinned to newest log (bottom) while autoScroll is active; surface subtle unread dot if scrolled away
    LaunchedEffect(latestStableId, autoScroll, visibleEntries.size, isProgrammaticJump) {
        if (autoScroll) {
            hasUnseenNewLogs = false
            lastSeenLatestId = latestStableId
            if (visibleEntries.isNotEmpty() && !isProgrammaticJump) {
                listState.scrollToItem(visibleEntries.lastIndex)
            }
        } else if (latestStableId > 0L && lastSeenLatestId > 0L && latestStableId != lastSeenLatestId) {
            hasUnseenNewLogs = true
        }
    }

    val isScrolledAway by remember {
        derivedStateOf { listState.canScrollForward }
    }
    val showJumpToBottomButton = visibleEntries.isNotEmpty() && (!autoScroll || isScrolledAway)

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Single Unified Header matching 03_server_log_monitor_v2.png (No double top bar!)
        LogUnifiedTopHeader(
            status = streamStatus,
            onTogglePause = {
                triggerHaptic()
                viewModel.togglePause()
            },
            onClearBuffer = {
                triggerHaptic()
                hasUnseenNewLogs = false
                lastSeenLatestId = -1L
                viewModel.clearLocalBuffer()
            },
            onOpenSettings = {
                triggerHaptic()
                onOpenSettings()
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 2. Compact 36 dp Filter Chip Row (no outer box): [✓ ALL] [FINANCE] [BRIDGE] [● ERRORS ONLY]
            LogFilterChipBar(
                activeFilter = activeFilter,
                onSelectFilter = { preset ->
                    triggerHaptic()
                    viewModel.selectFilter(preset)
                }
            )

            // 3. Framed Log List & Footer Container matching 03_server_log_monitor_v2.png
            Surface(
                color = if (nexaColors.isDark) Color(0xFF0C1118) else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, nexaColors.cardBorder),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when {
                            visibleEntries.isNotEmpty() -> {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .nestedScroll(scrollBreakConnection),
                                    contentPadding = PaddingValues(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        count = visibleEntries.size,
                                        key = { idx -> visibleEntries[idx].stableId }
                                    ) { idx ->
                                        val entry = visibleEntries.getOrNull(idx) ?: return@items
                                        LogRecordCard(
                                            entry = entry,
                                            formattedTime = timeFormat.format(Date(entry.timestampMs))
                                        )
                                    }
                                }

                                // Subtle small circular jump-to-bottom arrow (Telegram-style smart auto-hide)
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = showJumpToBottomButton,
                                    enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f, animationSpec = tween(150)),
                                    exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.8f, animationSpec = tween(140)),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 12.dp, bottom = 12.dp)
                                ) {
                                    LogJumpToBottomButton(
                                        hasUnseenLogs = hasUnseenNewLogs,
                                        onClick = {
                                            triggerHaptic()
                                            hasUnseenNewLogs = false
                                            lastSeenLatestId = visibleEntries.lastOrNull()?.stableId ?: -1L
                                            viewModel.setAutoScroll(true)
                                            coroutineScope.launch {
                                                val target = visibleEntries.lastIndex
                                                if (target >= 0) {
                                                    isProgrammaticJump = true
                                                    try {
                                                        listState.animateScrollToItem(target)
                                                    } finally {
                                                        isProgrammaticJump = false
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            streamStatus == LogStreamStatus.CONNECTING || streamStatus == LogStreamStatus.AWAITING_BIOMETRIC -> {
                                LogStatusOverlay(
                                    isLoading = true,
                                    title = if (streamStatus == LogStreamStatus.AWAITING_BIOMETRIC) {
                                        "Menunggu Verifikasi Sidik Jari"
                                    } else {
                                        "Menyambungkan ke /io/logs..."
                                    },
                                    subtitle = "Koneksi B on-demand diamankan dengan tiket Ed25519 sekali pakai (TTL 45 detik).",
                                    actionLabel = null,
                                    onAction = {}
                                )
                            }

                            streamStatus == LogStreamStatus.ERROR -> {
                                val needsSettings = enrollmentStatus != "ACTIVE"
                                LogStatusOverlay(
                                    isLoading = false,
                                    title = if (needsSettings) "Perangkat Belum Terdaftar" else "Aliran Log Terhenti",
                                    subtitle = errorMessage ?: "Gagal membuka aliran /io/logs.",
                                    actionLabel = if (needsSettings) "Buka Pengaturan & Enroll" else "Verifikasi & Sambung Ulang",
                                    actionIcon = if (needsSettings) Icons.Default.Settings else Icons.Default.Refresh,
                                    onAction = {
                                        triggerHaptic()
                                        if (needsSettings) {
                                            onOpenSettings()
                                        } else {
                                            val activity = context.findActivity() as? FragmentActivity
                                            viewModel.retryConnection(activity)
                                        }
                                    }
                                )
                            }

                            else -> {
                                LogStatusOverlay(
                                    isLoading = false,
                                    title = "Buffer Log Kosong (${activeFilter.label})",
                                    subtitle = if (streamStatus == LogStreamStatus.PAUSED) {
                                        "Aliran log sedang dijeda (CREDIT ditahan). Ketuk tombol Play di atas untuk melanjutkan."
                                    } else {
                                        "Menunggu baris log baru dari journald server..."
                                    },
                                    actionLabel = if (streamStatus == LogStreamStatus.IDLE_DISCONNECTED) {
                                        "Buka Koneksi /io/logs"
                                    } else null,
                                    onAction = {
                                        triggerHaptic()
                                        val activity = context.findActivity() as? FragmentActivity
                                        viewModel.retryConnection(activity)
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder)

                    // 4. Accurate Footer matching 03_server_log_monitor_v2.png (Zero hallucinated metrics)
                    LogAccurateFooter(
                        bufferCount = totalBufferedCount,
                        bufferCapacity = viewModel.logBuffer.capacity,
                        skippedCount = skippedCount,
                        streamStatus = streamStatus
                    )
                }
            }
        }
    }
}

/**
 * Slim single-row unified header for LOGS tab (fits 360-384 dp cleanly):
 * [Visor] N.E.X.A / ● Log Aktif     [⏸][🗑][⚙] (36 dp visual, 44 dp touch target)
 */
@Composable
private fun LogUnifiedTopHeader(
    status: LogStreamStatus,
    onTogglePause: () -> Unit,
    onClearBuffer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val (statusText, statusColor) = when (status) {
        LogStreamStatus.LIVE -> "Log Aktif" to nexaColors.emerald
        LogStreamStatus.PAUSED -> "Dijeda" to nexaColors.amber
        LogStreamStatus.CONNECTING, LogStreamStatus.AWAITING_BIOMETRIC -> "Tiket Auth" to nexaColors.amber
        LogStreamStatus.ERROR, LogStreamStatus.IDLE_DISCONNECTED -> "Siaga" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    val visorAvatarState = when (status) {
        LogStreamStatus.LIVE -> NexaAvatarState.IDLE
        LogStreamStatus.CONNECTING, LogStreamStatus.AWAITING_BIOMETRIC -> NexaAvatarState.THINKING
        else -> NexaAvatarState.ALERT
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            val isPaused = status == LogStreamStatus.PAUSED
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NexaVisorBadge(
                        eyeColor = nexaColors.visorEye,
                        avatarState = visorAvatarState,
                        modifier = Modifier.size(width = 38.dp, height = 28.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = "N.E.X.A",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LogSquareIconButton(
                        icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Lanjutkan aliran log" else "Jeda aliran log (tahan CREDIT)",
                        isActive = isPaused,
                        activeColor = nexaColors.amber,
                        onClick = onTogglePause
                    )
                    LogSquareIconButton(
                        icon = Icons.Default.DeleteOutline,
                        contentDescription = "Bersihkan buffer log lokal",
                        isActive = false,
                        activeColor = MaterialTheme.colorScheme.primary,
                        onClick = onClearBuffer
                    )
                    LogSquareIconButton(
                        icon = Icons.Default.Settings,
                        contentDescription = "Pengaturan dan Keamanan",
                        isActive = false,
                        activeColor = MaterialTheme.colorScheme.primary,
                        onClick = onOpenSettings
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun LogJumpToBottomButton(
    hasUnseenLogs: Boolean,
    onClick: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val surfaceBg = if (nexaColors.isDark) {
        Color(0xE0141D2A)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    }
    val borderColor = if (hasUnseenLogs) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    } else {
        nexaColors.cardBorder.copy(alpha = 0.9f)
    }
    val iconTint = if (hasUnseenLogs) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = Modifier.size(40.dp)) {
        Surface(
            color = surfaceBg,
            shape = CircleShape,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .size(40.dp)
                .clickable(role = Role.Button, onClick = onClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Lompat ke log terbaru di bawah",
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (hasUnseenLogs) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun LogSquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val bg = when {
        isActive && nexaColors.isDark -> activeColor.copy(alpha = 0.14f)
        isActive -> activeColor.copy(alpha = 0.12f)
        nexaColors.isDark -> Color(0xFF111720)
        else -> MaterialTheme.colorScheme.surface
    }
    val border = if (isActive) activeColor else nexaColors.cardBorder
    val tint = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant

    // 44 dp transparent hit target around crisp 36 dp visual button (comfortable thumb tap without bulk)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, border),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LogFilterChipBar(
    activeFilter: LogFilterPreset,
    onSelectFilter: (LogFilterPreset) -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LogFilterPreset.entries.forEach { preset ->
            val isSelected = preset == activeFilter
            val chipBg = when {
                isSelected -> nexaColors.primaryButtonFill
                nexaColors.isDark -> Color(0xFF131B26)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val chipBorder = if (isSelected) nexaColors.primaryButtonFill else nexaColors.cardBorder
            val textColor = if (isSelected) {
                nexaColors.onPrimaryButtonFill
            } else {
                MaterialTheme.colorScheme.onSurface
            }

            Surface(
                color = chipBg,
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, chipBorder),
                modifier = Modifier
                    .height(36.dp)
                    .semantics { selected = isSelected }
                    .clickable(role = Role.Tab) { onSelectFilter(preset) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(13.dp)
                        )
                    } else if (preset == LogFilterPreset.ERRORS_ONLY) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(nexaColors.crimson, CircleShape)
                        )
                    }

                    Text(
                        text = preset.label,
                        style = NexaMonoTypography.badge,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }
            }
        }
    }
}

/**
 * Individual Log Record Card matching 03_server_log_monitor_v2.png:
 * - Separate Priority badge slot ([INFO], [WARN], [ERROR], [DEBUG])
 * - Separate Category badge slot ([FINANCE], [BRIDGE], [AUTH], [SYNTHETIC])
 * - Color-coded card background, border, and monospace message text
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogRecordCard(
    entry: LogEntry,
    formattedTime: String
) {
    val nexaColors = LocalNexaColors.current
    val isError = entry.prio <= LogBinaryProtocol.PRIO_ERROR
    val isWarn = entry.prio == LogBinaryProtocol.PRIO_WARN
    val isSynthetic = entry.isSynthetic
    val isFinance = (entry.catFlags and LogBinaryProtocol.CAT_FINANCE) != 0
    val isBridge = (entry.catFlags and LogBinaryProtocol.CAT_BRIDGE) != 0

    val cardBg = when {
        isError && nexaColors.isDark -> Color(0xFF231116)
        isError -> Color(0xFFFEE2E2)
        isSynthetic && nexaColors.isDark -> Color(0xFF1F180D)
        isSynthetic -> Color(0xFFFFF3E0)
        nexaColors.isDark -> Color(0xFF121923)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    val cardBorder = when {
        isError -> nexaColors.crimson.copy(alpha = 0.65f)
        isSynthetic -> nexaColors.amber.copy(alpha = 0.65f)
        else -> nexaColors.cardBorder
    }

    val messageColor = when {
        isError -> nexaColors.crimson
        isSynthetic || isWarn -> nexaColors.amber
        isFinance -> nexaColors.emerald
        isBridge -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val unitColor = when (entry.unitId) {
        LogBinaryProtocol.UNIT_NEXA_SERVER -> nexaColors.violet
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = cardBg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Top Metadata FlowRow: wraps cleanly at large accessibility font scales (U-M3)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedTime,
                    style = NexaMonoTypography.codeLine,
                    color = if (isError) nexaColors.crimson else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "[${entry.unitName}]",
                    style = NexaMonoTypography.codeLine,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) nexaColors.crimson else unitColor
                )

                // Slot 1: Priority Badge
                PriorityBadgePill(prioBadge = entry.priorityBadge)

                // Slot 2: Category Badge (Separate Slot)
                entry.categoryBadge?.let { catBadge ->
                    CategoryBadgePill(catBadge = catBadge)
                }
            }

            // Bottom Log Message Row
            Text(
                text = entry.message,
                style = NexaMonoTypography.codeLine.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = messageColor
            )
        }
    }
}

@Composable
private fun PriorityBadgePill(prioBadge: String) {
    val nexaColors = LocalNexaColors.current
    val (bg, border, text) = when (prioBadge) {
        "ERROR" -> Triple(
            if (nexaColors.isDark) Color(0xFF33151C) else Color(0xFFFECACA),
            nexaColors.crimson.copy(alpha = 0.75f),
            nexaColors.crimson
        )
        "WARN" -> Triple(
            if (nexaColors.isDark) Color(0xFF2C200E) else Color(0xFFFDE68A),
            nexaColors.amber.copy(alpha = 0.75f),
            nexaColors.amber
        )
        else -> Triple(
            if (nexaColors.isDark) Color(0xFF1A2433) else MaterialTheme.colorScheme.surfaceVariant,
            nexaColors.cardBorder,
            MaterialTheme.colorScheme.onSurface
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = "[$prioBadge]",
            style = NexaMonoTypography.badge.copy(fontSize = 11.sp),
            color = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun CategoryBadgePill(catBadge: String) {
    val nexaColors = LocalNexaColors.current
    val (bg, border, text) = when (catBadge) {
        "FINANCE" -> Triple(
            if (nexaColors.isDark) Color(0xFF0A291E) else Color(0xFFD9F5E5),
            nexaColors.emerald.copy(alpha = 0.75f),
            nexaColors.emerald
        )
        "BRIDGE" -> Triple(
            if (nexaColors.isDark) Color(0xFF082632) else Color(0xFFD6F3F8),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            MaterialTheme.colorScheme.primary
        )
        "SYNTHETIC" -> Triple(
            if (nexaColors.isDark) Color(0xFF2C200E) else Color(0xFFFDE68A),
            nexaColors.amber.copy(alpha = 0.75f),
            nexaColors.amber
        )
        else -> Triple(
            if (nexaColors.isDark) Color(0xFF192336) else MaterialTheme.colorScheme.surfaceVariant,
            nexaColors.cardBorder,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = "[$catBadge]",
            style = NexaMonoTypography.badge.copy(fontSize = 11.sp),
            color = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun LogAccurateFooter(
    bufferCount: Int,
    bufferCapacity: Int,
    skippedCount: Int,
    streamStatus: LogStreamStatus
) {
    val nexaColors = LocalNexaColors.current
    val formattedCap = if (bufferCapacity == 2000) "2.000" else bufferCapacity.toString()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = buildAnnotatedString {
                append("Buffer: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                    append("$bufferCount / $formattedCap")
                }
                append(" baris • Dilewati: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = nexaColors.amber)) {
                    append(skippedCount.toString())
                }
                append(" • Berhenti otomatis saat layar mati")
            },
            style = NexaMonoTypography.codeLine.copy(fontSize = 11.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = when (streamStatus) {
                LogStreamStatus.LIVE -> "WSS-SECURE"
                LogStreamStatus.PAUSED -> "WSS-SECURE • PAUSED (CREDIT=0)"
                LogStreamStatus.CONNECTING, LogStreamStatus.AWAITING_BIOMETRIC -> "WSS-SECURE • AUTHENTICATING"
                else -> "WSS-SECURE • ON-DEMAND IDLE"
            },
            style = NexaMonoTypography.badge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun LogStatusOverlay(
    isLoading: Boolean,
    title: String,
    subtitle: String,
    actionLabel: String?,
    actionIcon: ImageVector = Icons.Default.Refresh,
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
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
