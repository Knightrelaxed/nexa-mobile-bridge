package com.nexa.mobilebridge.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.SolidColor
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
import com.nexa.mobilebridge.data.logs.LogBinaryProtocol
import com.nexa.mobilebridge.data.logs.LogEntry
import com.nexa.mobilebridge.data.logs.LogFilterPreset
import com.nexa.mobilebridge.data.logs.LogStreamStatus
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import com.nexa.mobilebridge.ui.components.NexaVisorBadge
import com.nexa.mobilebridge.ui.components.NexaVisorVisualState
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
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val autoScroll by viewModel.autoScrollEnabled.collectAsStateWithLifecycle()
    val enrollmentStatus by viewModel.enrollmentStatus.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticEnabled.collectAsStateWithLifecycle()
    var isSearchExpanded by remember { mutableStateOf(false) }

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
        // 1. Single Unified Header with Search, Pause, Clear, Settings
        LogUnifiedTopHeader(
            status = streamStatus,
            isSearchActive = isSearchExpanded || searchQuery.isNotEmpty(),
            onToggleSearch = {
                triggerHaptic()
                if (isSearchExpanded) {
                    isSearchExpanded = false
                    viewModel.clearSearchQuery()
                } else {
                    isSearchExpanded = true
                }
            },
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

        // 2. Slim Full-Width Filter Chip Strip (Opsi A: ALL | ERRORS | CORE | BRIDGE | AUTH)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                LogFilterChipBar(
                    activeFilter = activeFilter,
                    onSelectFilter = { preset ->
                        triggerHaptic()
                        viewModel.selectFilter(preset)
                    }
                )
            }

            AnimatedVisibility(
                visible = isSearchExpanded || searchQuery.isNotEmpty(),
                enter = expandVertically(tween(150)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
            ) {
                LogLocalSearchBar(
                    query = searchQuery,
                    matchCount = visibleEntries.size,
                    onQueryChange = { viewModel.updateSearchQuery(it) },
                    onClearOrClose = {
                        triggerHaptic()
                        if (searchQuery.isNotEmpty()) {
                            viewModel.clearSearchQuery()
                        } else {
                            isSearchExpanded = false
                        }
                    }
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder.copy(alpha = 0.65f))

        // 3. Edge-to-Edge Flat Console Stream (Option A: zero outer frame, zero per-row cards)
        val streamBg = if (nexaColors.isDark) Color(0xFF0A0F16) else MaterialTheme.colorScheme.background
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(streamBg)
        ) {
            when {
                visibleEntries.isNotEmpty() -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBreakConnection),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        items(
                            count = visibleEntries.size,
                            key = { idx -> visibleEntries[idx].stableId }
                        ) { idx ->
                            val entry = visibleEntries.getOrNull(idx) ?: return@items
                            val formattedTime = timeFormat.format(Date(entry.timestampMs))
                            FlatLogRow(
                                entry = entry,
                                formattedTime = formattedTime,
                                onCopyRow = {
                                    triggerHaptic()
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    val line = "$formattedTime [${entry.unitName}] [${entry.priorityBadge}] ${entry.message}"
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Log N.E.X.A", line))
                                }
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
                            .padding(end = 12.dp, bottom = 10.dp)
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

                searchQuery.isNotBlank() -> {
                    LogStatusOverlay(
                        isLoading = false,
                        title = "Tidak Ada Log yang Cocok",
                        subtitle = "Kata kunci \"$searchQuery\" tidak ditemukan pada filter ${activeFilter.label} ($totalBufferedCount baris di buffer).",
                        actionLabel = "Reset Pencarian",
                        actionIcon = Icons.Default.Close,
                        onAction = {
                            triggerHaptic()
                            viewModel.clearSearchQuery()
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
                            "Menunggu baris log baru dari server..."
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

        HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder.copy(alpha = 0.65f))

        // 4. Compact Single-Line Status Footer (~26 dp)
        LogAccurateFooter(
            bufferCount = totalBufferedCount,
            bufferCapacity = viewModel.logBuffer.capacity,
            skippedCount = skippedCount,
            streamStatus = streamStatus
        )
    }
}

/**
 * Slim single-row unified header for LOGS tab (fits 360-384 dp cleanly):
 * [Visor] N.E.X.A / ● Log Aktif     [⏸][🗑][⚙] (36 dp visual, 44 dp touch target)
 */
@Composable
private fun LogUnifiedTopHeader(
    status: LogStreamStatus,
    isSearchActive: Boolean,
    onToggleSearch: () -> Unit,
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

    val visorVisualState = when (status) {
        LogStreamStatus.LIVE -> NexaVisorVisualState.IDLE
        LogStreamStatus.CONNECTING -> NexaVisorVisualState.THINKING
        LogStreamStatus.AWAITING_BIOMETRIC -> NexaVisorVisualState.STEP_UP
        LogStreamStatus.ERROR -> NexaVisorVisualState.ERROR
        LogStreamStatus.PAUSED,
        LogStreamStatus.IDLE_DISCONNECTED -> NexaVisorVisualState.OFFLINE
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
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NexaVisorBadge(
                        visualState = visorVisualState,
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
                        icon = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (isSearchActive) "Tutup pencarian log" else "Cari kata kunci log",
                        isActive = isSearchActive,
                        activeColor = MaterialTheme.colorScheme.primary,
                        onClick = onToggleSearch
                    )
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
private fun LogLocalSearchBar(
    query: String,
    matchCount: Int,
    onQueryChange: (String) -> Unit,
    onClearOrClose: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val inputBg = if (nexaColors.isDark) Color(0xFF111824) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (query.isNotEmpty()) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    } else {
        nexaColors.cardBorder
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
    ) {
        Surface(
            color = inputBg,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Cari: finance, calendar, gmail, docs, task...",
                            style = NexaMonoTypography.codeLine.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = NexaMonoTypography.codeLine.copy(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (query.isNotEmpty()) {
                    Text(
                        text = "$matchCount",
                        style = NexaMonoTypography.badge.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = if (query.isNotEmpty()) "Hapus kata kunci" else "Tutup pencarian",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(role = Role.Button, onClick = onClearOrClose)
                )
            }
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

    // 40 dp hit target around crisp 34 dp visual button (4 header actions fit comfortably on 360 dp phones)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, border),
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(17.dp)
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
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(1.dp, chipBorder),
                modifier = Modifier
                    .height(30.dp)
                    .semantics { selected = isSelected }
                    .clickable(role = Role.Tab) { onSelectFilter(preset) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(12.dp)
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
                        style = NexaMonoTypography.badge.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }
            }
        }
    }
}

/**
 * Flat Console Stream log row (Option A - Datadog / Android Logcat style):
 * - Edge-to-edge layout with 2.5 dp color-coded left accent bar and hairline 0.5 dp divider
 * - Calm metadata line for INFO; compact colored badges only for WARN, ERROR, FINANCE, BRIDGE, AUTH, SYNTHETIC
 * - Tap any row to copy the log line directly to clipboard
 */
@Composable
private fun FlatLogRow(
    entry: LogEntry,
    formattedTime: String,
    onCopyRow: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val isError = entry.prio <= LogBinaryProtocol.PRIO_ERROR
    val isWarn = entry.prio == LogBinaryProtocol.PRIO_WARN
    val isSynthetic = entry.isSynthetic
    val isFinance = (entry.catFlags and LogBinaryProtocol.CAT_FINANCE) != 0
    val isCore = (entry.catFlags and LogBinaryProtocol.CAT_CORE) != 0
    val isBridge = (entry.catFlags and LogBinaryProtocol.CAT_BRIDGE) != 0
    val isAuth = (entry.catFlags and LogBinaryProtocol.CAT_AUTH) != 0

    val rowBg = when {
        isError && nexaColors.isDark -> Color(0xFF210F14)
        isError -> Color(0xFFFEE2E2)
        (isSynthetic || isWarn) && nexaColors.isDark -> Color(0xFF1D160B)
        isSynthetic || isWarn -> Color(0xFFFFF3E0)
        else -> Color.Transparent
    }

    val accentBarColor = when {
        isError -> nexaColors.crimson
        isSynthetic || isWarn -> nexaColors.amber
        isFinance -> nexaColors.emerald
        isCore -> nexaColors.violet
        isAuth -> nexaColors.amber
        isBridge -> MaterialTheme.colorScheme.primary
        else -> nexaColors.cardBorder.copy(alpha = 0.55f)
    }

    val messageColor = when {
        isError -> nexaColors.crimson
        isSynthetic || isWarn -> nexaColors.amber
        isFinance -> nexaColors.emerald
        isBridge -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val unitColor = when {
        isError -> nexaColors.crimson
        entry.unitId == LogBinaryProtocol.UNIT_NEXA_SERVER -> nexaColors.violet
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onCopyRow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .background(accentBarColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = formattedTime,
                        style = NexaMonoTypography.codeLine.copy(fontSize = 10.5.sp),
                        color = if (isError) nexaColors.crimson else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    )

                    Text(
                        text = entry.unitName,
                        style = NexaMonoTypography.codeLine.copy(fontSize = 10.5.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = unitColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isError || isWarn) {
                        InlineLogTag(
                            label = entry.priorityBadge,
                            textColor = if (isError) nexaColors.crimson else nexaColors.amber,
                            bgColor = if (isError) {
                                nexaColors.crimson.copy(alpha = 0.16f)
                            } else {
                                nexaColors.amber.copy(alpha = 0.16f)
                            }
                        )
                    }

                    entry.categoryBadge?.let { catBadge ->
                        val catTint = when (catBadge) {
                            "FINANCE" -> nexaColors.emerald
                            "CORE" -> nexaColors.violet
                            "BRIDGE" -> MaterialTheme.colorScheme.primary
                            "AUTH", "SYNTHETIC" -> nexaColors.amber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        InlineLogTag(
                            label = catBadge,
                            textColor = catTint,
                            bgColor = catTint.copy(alpha = 0.16f)
                        )
                    }
                }

                Text(
                    text = entry.message,
                    style = NexaMonoTypography.codeLine.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = messageColor
                )
            }
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = nexaColors.cardBorder.copy(alpha = 0.28f)
        )
    }
}

@Composable
private fun InlineLogTag(
    label: String,
    textColor: Color,
    bgColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            style = NexaMonoTypography.badge.copy(fontSize = 9.5.sp),
            fontWeight = FontWeight.Bold,
            color = textColor
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
    val statusLabel = when (streamStatus) {
        LogStreamStatus.LIVE -> "WSS-SECURE"
        LogStreamStatus.PAUSED -> "PAUSED"
        LogStreamStatus.CONNECTING, LogStreamStatus.AWAITING_BIOMETRIC -> "AUTH..."
        else -> "IDLE"
    }
    val statusColor = when (streamStatus) {
        LogStreamStatus.LIVE -> MaterialTheme.colorScheme.primary
        LogStreamStatus.PAUSED -> nexaColors.amber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                    append("$bufferCount/$formattedCap")
                }
                append(" baris")
                if (skippedCount > 0) {
                    append(" • Lewat ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = nexaColors.amber)) {
                        append(skippedCount.toString())
                    }
                }
            },
            style = NexaMonoTypography.codeLine.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = statusLabel,
            style = NexaMonoTypography.badge.copy(fontSize = 10.5.sp),
            fontWeight = FontWeight.Bold,
            color = statusColor
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
