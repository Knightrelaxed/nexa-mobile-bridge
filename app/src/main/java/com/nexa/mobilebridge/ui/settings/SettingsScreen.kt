package com.nexa.mobilebridge.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobilebridge.core.security.BiometricAuthHelper
import com.nexa.mobilebridge.core.security.KeystoreKeyState
import com.nexa.mobilebridge.core.security.SecureScreenEffect
import com.nexa.mobilebridge.core.security.findFragmentActivity
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.data.local.AccentPreset
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.data.local.ThemeMode
import com.nexa.mobilebridge.theme.LocalNexaColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Minimalist grouped Settings screen (Full English UI):
 * - Top summary card (live TLS status + device hardware key state)
 * - Frequency-ordered groups: Appearance -> Notifications & Feedback -> Server Connection -> Device Security -> Advanced
 * - Full 32-octet SHA-256 fingerprint inside DeviceSecurityDetailSheet
 * - View-first Server Connection with fingerprint-gated Edit mode & sticky Save bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onOpenBridgeStatus: () -> Unit = {},
    onOpenServerLogs: () -> Unit = {},
    onOpenRemoteTerminal: () -> Unit = {}
) {
    // Automatically enforces FLAG_SECURE on release builds; allows screenshots during debug/testing.
    SecureScreenEffect(allowInDebugBuild = true)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val nexaColors = LocalNexaColors.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val isDebuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    var showSecuritySheet by remember { mutableStateOf(false) }
    var showEnrollDialog by remember { mutableStateOf(false) }
    var enrollCodeInput by remember { mutableStateOf("") }
    var activePreviewRingtone by remember { mutableStateOf<Ringtone?>(null) }
    var isPlayingSoundPreview by remember { mutableStateOf(false) }

    LaunchedEffect(activePreviewRingtone, isPlayingSoundPreview) {
        val currentRingtone = activePreviewRingtone
        if (currentRingtone != null && isPlayingSoundPreview) {
            delay(300L)
            while (runCatching { currentRingtone.isPlaying }.getOrDefault(false)) {
                delay(250L)
            }
            isPlayingSoundPreview = false
            activePreviewRingtone = null
        }
    }

    LaunchedEffect(uiState.lastEnrollSuccessTick) {
        if (uiState.lastEnrollSuccessTick > 0) {
            enrollCodeInput = ""
            showEnrollDialog = false
        }
    }

    DisposableEffect(Unit) {
        viewModel.onEnterSettings()
        onDispose {
            runCatching { activePreviewRingtone?.stop() }
            activePreviewRingtone = null
            isPlayingSoundPreview = false
            viewModel.onLeaveSettings()
        }
    }

    val soundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        com.nexa.mobilebridge.MainActivity.clearExternalPickerLaunched()
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val displayName = resolveAudioDisplayName(context, uri)
            viewModel.onChatNotificationSoundSelected(uri.toString(), displayName)
            viewModel.setStatusNotice("Chat notification tone updated: $displayName")
        }
    }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.US) }
    val enrolledDateText = if (uiState.enrolledAtEpochSec > 0L) {
        "Paired ${dateFormatter.format(Date(uiState.enrolledAtEpochSec * 1000L))}"
    } else {
        "Not paired with server"
    }

    val isKeyInvalidated = uiState.keySnapshot.state == KeystoreKeyState.INVALIDATED ||
        uiState.enrollmentStatus == "INVALIDATED"

    val (identitySummaryTitle, identityBadgeLabel, identityStatusColor) = when {
        isKeyInvalidated -> Triple("Device key invalidated", "Invalidated", nexaColors.crimson)
        uiState.enrollmentStatus == "ACTIVE" -> Triple("Device key active · P-256", "Active · P-256", nexaColors.emerald)
        else -> Triple("Device not paired", "Not paired", nexaColors.amber)
    }

    val (connStatusText, connStatusColor) = when (val cs = uiState.connectionState) {
        is ConnectionState.Connected -> "Connected · TLS 1.3" to nexaColors.emerald
        is ConnectionState.Connecting -> "Connecting..." to nexaColors.amber
        is ConnectionState.Reconnecting -> "Reconnecting #${cs.attempt}" to nexaColors.amber
        is ConnectionState.AuthFailed -> "Auth failed" to nexaColors.crimson
        is ConnectionState.Disconnected -> "Disconnected" to nexaColors.crimson
    }

    fun requestTokenPeek() {
        if (uiState.tokenRevealSecondsLeft > 0) {
            viewModel.hideTokenImmediately()
            return
        }
        val activity = context.findFragmentActivity()
        if (activity == null) {
            viewModel.setStatusNotice("Biometric prompt is unavailable.")
            return
        }
        BiometricAuthHelper.authenticateGuard(
            activity = activity,
            title = "Reveal Access Token",
            subtitle = "Token will be visible for 10 seconds",
            onSuccess = { viewModel.startTokenReveal10Seconds() },
            onError = { err -> viewModel.setStatusNotice("Cancelled: $err") }
        )
    }

    fun requestServerEditUnlock() {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            viewModel.setStatusNotice("Biometric prompt is unavailable.")
            return
        }
        BiometricAuthHelper.authenticateGuard(
            activity = activity,
            title = "Edit Server Configuration",
            subtitle = "Verify fingerprint to edit N.E.X.A server settings",
            onSuccess = { viewModel.unlockServerEditAfterBiometric() },
            onError = { err -> viewModel.setStatusNotice("Verification cancelled: $err") }
        )
    }

    fun requestApplyServerChanges() {
        if (uiState.hapticEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        if (uiState.enrollmentStatus == "ACTIVE") {
            val activity = context.findFragmentActivity()
            if (activity != null) {
                BiometricAuthHelper.authenticateGuard(
                    activity = activity,
                    title = "Apply Server Configuration",
                    subtitle = "Confirm fingerprint to save connection settings",
                    onSuccess = { viewModel.saveSettings() },
                    onError = { err -> viewModel.setStatusNotice("Cancelled: $err") }
                )
            } else {
                // Fail-closed: never bypass biometric verification
                viewModel.setStatusNotice("Fingerprint verification is required to save settings.")
            }
        } else {
            viewModel.saveSettings()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!isDebuggable) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = "Protected screen",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                HorizontalDivider(
                    thickness = 0.75.dp,
                    color = nexaColors.cardBorder.copy(alpha = 0.55f)
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = uiState.hasUnsavedServerChanges,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(180)) + fadeIn(tween(140)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(160)) + fadeOut(tween(120))
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                        HorizontalDivider(color = nexaColors.cardBorder.copy(alpha = 0.65f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.discardServerChanges() },
                                modifier = Modifier
                                    .weight(0.9f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, nexaColors.cardBorder)
                            ) {
                                Text("Cancel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Button(
                                onClick = { requestApplyServerChanges() },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = nexaColors.primaryButtonFill,
                                    contentColor = nexaColors.onPrimaryButtonFill
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                if (uiState.enrollmentStatus == "ACTIVE") {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = if (uiState.enrollmentStatus == "ACTIVE") {
                                        "Apply (Fingerprint)"
                                    } else {
                                        "Save Connection"
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (!uiState.statusNotice.isNullOrBlank()) {
                item(key = "status_notice") {
                    SettingsNoticeBanner(
                        message = uiState.statusNotice.orEmpty(),
                        onDismiss = { viewModel.clearStatusNotice() }
                    )
                }
            }

            // =================================================================
            // 1. SUMMARY
            // =================================================================
            item(key = "section_summary") {
                Column {
                SettingsGroupCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onOpenBridgeStatus() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatusIndicatorDot(color = connStatusColor)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = connStatusText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = extractDisplayHost(uiState.savedServerUrl.ifBlank { uiState.serverUrl }),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Status",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    SettingsRowDivider(insetStart = false)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { showSecuritySheet = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatusIndicatorDot(color = identityStatusColor)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = identitySummaryTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = uiState.deviceName,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isKeyInvalidated) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = nexaColors.crimson.copy(alpha = if (nexaColors.isDark) 0.14f else 0.10f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, nexaColors.crimson.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = nexaColors.crimson,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Device key invalidated",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = nexaColors.crimson
                                )
                            }
                            Text(
                                text = "System fingerprints changed. Regenerate the P-256 key and run nexa-enroll re-pair on your server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Button(
                                onClick = { viewModel.regenerateHardwareKey() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = nexaColors.crimson,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("Regenerate Hardware Key", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            }

            // =================================================================
            // 2. APPEARANCE
            // =================================================================
            item(key = "section_appearance") {
            Column {
                SettingsGroupHeader(title = "Appearance")
                SettingsGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Theme",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SettingsSegmentedControl(
                            options = listOf(
                                ThemeMode.SYSTEM to "System",
                                ThemeMode.DARK to "Dark",
                                ThemeMode.LIGHT to "Light"
                            ),
                            selected = uiState.themeMode,
                            onSelect = { mode ->
                                if (uiState.hapticEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                viewModel.onThemeModeSelected(mode)
                            },
                            emphasizeWithAccentFill = true
                        )
                    }

                    SettingsRowDivider(insetStart = false)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Accent color",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = uiState.accentPreset.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            AccentPreset.entries.forEach { preset ->
                                val isSelected = uiState.accentPreset == preset
                                val swatchColor = nexaColors.swatchFor(preset, selected = isSelected)
                                val checkColor = if (nexaColors.isDark) Color(0xFF021014) else Color.White

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .semantics {
                                            selected = isSelected
                                            contentDescription = "${preset.displayName} accent"
                                        }
                                        .clickable(role = Role.RadioButton) {
                                            if (uiState.hapticEnabled) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            viewModel.onAccentPresetSelected(preset)
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onBackground
                                                } else {
                                                    nexaColors.cardBorder.copy(alpha = 0.6f)
                                                },
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = checkColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = preset.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }

            // =================================================================
            // 3. NOTIFICATIONS & FEEDBACK
            // =================================================================
            item(key = "section_notifications") {
            Column {
                SettingsGroupHeader(title = "Notifications & Feedback")
                SettingsGroupCard {
                    SettingsListRow(
                        leadingIcon = Icons.Default.Tune,
                        title = "Haptic feedback",
                        subtitle = "Touch vibration on primary actions",
                        trailing = {
                            Switch(
                                checked = uiState.hapticEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.onHapticToggled(enabled)
                                    if (enabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            )
                        }
                    )

                    SettingsRowDivider(insetStart = true)

                    SettingsListRow(
                        leadingIcon = Icons.Default.Audiotrack,
                        title = "Chat notification tone",
                        subtitle = uiState.chatNotificationSoundLabel.let {
                            if (it == "Default Sistem") "System default" else it
                        },
                        onClick = {
                            com.nexa.mobilebridge.MainActivity.markExternalPickerLaunched()
                            soundPickerLauncher.launch(arrayOf("audio/*"))
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (uiState.chatNotificationSoundUri.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            runCatching { activePreviewRingtone?.stop() }
                                            activePreviewRingtone = null
                                            isPlayingSoundPreview = false
                                            viewModel.onChatNotificationSoundSelected("", "System default")
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    ) {
                                        Text("Reset", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (isPlayingSoundPreview) {
                                            runCatching { activePreviewRingtone?.stop() }
                                            activePreviewRingtone = null
                                            isPlayingSoundPreview = false
                                        } else {
                                            runCatching { activePreviewRingtone?.stop() }
                                            val targetUri = uiState.chatNotificationSoundUri
                                                .takeIf { it.isNotBlank() }
                                                ?.let { Uri.parse(it) }
                                                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                            val ringtone = runCatching {
                                                RingtoneManager.getRingtone(context, targetUri)
                                            }.getOrNull()
                                            if (ringtone != null) {
                                                runCatching { ringtone.play() }
                                                activePreviewRingtone = ringtone
                                                isPlayingSoundPreview = true
                                            } else {
                                                viewModel.setStatusNotice("Could not play notification tone preview.")
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlayingSoundPreview) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlayingSoundPreview) "Stop preview" else "Play preview",
                                        tint = if (isPlayingSoundPreview) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    )

                    SettingsRowDivider(insetStart = true)

                    SettingsListRow(
                        leadingIcon = Icons.Default.Notifications,
                        title = "System notification settings",
                        subtitle = "Sound, vibration, and lock screen per channel",
                        showChevron = true,
                        onClick = {
                            com.nexa.mobilebridge.MainActivity.markExternalPickerLaunched()
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure { viewModel.setStatusNotice("Unable to open system notification settings.") }
                        }
                    )
                }
            }
            }

            // =================================================================
            // 4. SERVER CONNECTION (View-first; Edit behind fingerprint)
            // =================================================================
            item(key = "section_server_connection") {
            Column {
                val showLockHeaderAction = uiState.isServerEditMode &&
                    uiState.enrollmentStatus == "ACTIVE" &&
                    !uiState.hasUnsavedServerChanges
                SettingsGroupHeader(
                    title = "Server Connection",
                    trailingActionLabel = if (showLockHeaderAction) "Done" else null,
                    onTrailingAction = if (showLockHeaderAction) {
                        { viewModel.discardServerChanges() }
                    } else null
                )
                SettingsGroupCard {
                    val isTokenVisible = uiState.tokenRevealSecondsLeft > 0

                    if (!uiState.isServerEditMode) {
                        SettingsListRow(
                            leadingIcon = Icons.Default.Public,
                            title = "Server domain",
                            subtitle = extractDisplayHost(uiState.serverUrl),
                            subtitleMonospace = true,
                            trailing = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Domain locked",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )

                        SettingsRowDivider(insetStart = true)

                        SettingsListRow(
                            leadingIcon = Icons.Default.Key,
                            title = "Access token",
                            subtitle = if (isTokenVisible) {
                                uiState.authToken.ifBlank { "Not set" }
                            } else if (uiState.authToken.isNotBlank()) {
                                "••••••••••••••••"
                            } else {
                                "Not set"
                            },
                            subtitleMonospace = isTokenVisible || uiState.authToken.isNotBlank(),
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isTokenVisible) {
                                        Text(
                                            text = "${uiState.tokenRevealSecondsLeft}s",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = nexaColors.amber,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { requestTokenPeek() },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (isTokenVisible) "Hide token" else "Reveal token for 10s",
                                            tint = if (isTokenVisible) nexaColors.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        )

                        SettingsRowDivider(insetStart = true)

                        SettingsListRow(
                            leadingIcon = Icons.Default.Lock,
                            title = "HMAC secret",
                            trailingText = if (uiState.hmacSecret.isNotBlank()) "Saved" else "Not set"
                        )

                        SettingsRowDivider(insetStart = true)

                        TestConnectionRow(
                            state = uiState.connectionTestState,
                            onTest = {
                                if (uiState.hapticEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                viewModel.testConnection()
                            }
                        )

                        SettingsRowDivider(insetStart = true)

                        SettingsListRow(
                            leadingIcon = Icons.Default.Edit,
                            title = "Edit configuration",
                            subtitle = "Fingerprint verification required",
                            showChevron = true,
                            onClick = { requestServerEditUnlock() }
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = uiState.serverUrl,
                                onValueChange = { viewModel.onServerUrlChanged(it) },
                                label = { Text("Server domain (WSS)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(
                                    value = uiState.authToken,
                                    onValueChange = { viewModel.onAuthTokenChanged(it) },
                                    label = { Text("Access token (Bearer)") },
                                    singleLine = true,
                                    visualTransformation = if (isTokenVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { requestTokenPeek() },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (isTokenVisible) "Hide token" else "Reveal token for 10s"
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = if (isTokenVisible) {
                                        "Token visible temporarily (${uiState.tokenRevealSecondsLeft}s left)"
                                    } else {
                                        "Reveal for 10s (fingerprint required)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isTokenVisible) nexaColors.amber else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            OutlinedTextField(
                                value = uiState.hmacSecret,
                                onValueChange = { viewModel.onHmacSecretChanged(it) },
                                label = { Text("HMAC secret") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        SettingsRowDivider(insetStart = false)

                        TestConnectionRow(
                            state = uiState.connectionTestState,
                            onTest = {
                                if (uiState.hapticEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                viewModel.testConnection()
                            }
                        )
                    }
                }
            }
            }

            // =================================================================
            // 5. DEVICE SECURITY
            // =================================================================
            item(key = "section_device_security") {
            Column {
                SettingsGroupHeader(title = "Device Security")
                SettingsGroupCard {
                    SettingsListRow(
                        leadingIcon = Icons.Default.Shield,
                        title = "Device identity & key",
                        subtitle = "Keystore ${uiState.keySnapshot.securityLevelLabel} · ${formatFingerprintPreview(uiState.keySnapshot.sha256ColonHex)}",
                        subtitleMonospace = true,
                        showChevron = true,
                        onClick = { showSecuritySheet = true }
                    )

                    SettingsRowDivider(insetStart = true)

                    SettingsListRow(
                        leadingIcon = Icons.Default.Fingerprint,
                        title = "Authentication",
                        subtitle = "Fingerprint required per sensitive action"
                    )

                    SettingsRowDivider(insetStart = true)

                    SettingsListRow(
                        leadingIcon = Icons.Default.Terminal,
                        title = if (uiState.enrollmentStatus == "ACTIVE") {
                            "Re-pair device"
                        } else {
                            "Pair device via CLI"
                        },
                        subtitle = "Sync hardware key via nexa-enroll",
                        showChevron = true,
                        onClick = {
                            viewModel.clearEnrollError()
                            showEnrollDialog = true
                        }
                    )
                }
            }
            }

            // =================================================================
            // 6. DIAGNOSTICS
            // =================================================================
            item(key = "section_diagnostics") {
            Column {
                SettingsGroupHeader(title = "Diagnostics")
                SettingsGroupCard {
                    SettingsListRow(
                        leadingIcon = Icons.Default.Dashboard,
                        title = "Bridge Status",
                        subtitle = "Connection, telemetry, permissions & service",
                        showChevron = true,
                        onClick = onOpenBridgeStatus
                    )

                    SettingsRowDivider(insetStart = true)

                    SettingsListRow(
                        leadingIcon = Icons.AutoMirrored.Filled.ReceiptLong,
                        title = "Server Logs",
                        subtitle = "Live on-demand log stream · Fingerprint",
                        showChevron = true,
                        onClick = onOpenServerLogs
                    )

                    SettingsRowDivider(insetStart = true)

                    SettingsListRow(
                        leadingIcon = Icons.Default.Terminal,
                        title = "Remote Terminal",
                        subtitle = "Isolated VPS shell · Fingerprint",
                        trailingText = uiState.killSwitchLabel,
                        trailingColor = if (uiState.isKillSwitchActive) {
                            nexaColors.emerald
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        showChevron = true,
                        onClick = onOpenRemoteTerminal
                    )
                }
            }
            }

            // =================================================================
            // 7. ADVANCED
            // =================================================================
            item(key = "section_advanced") {
            Column {
                SettingsGroupHeader(title = "Advanced")
                SettingsGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Heartbeat (screen on)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SettingsSegmentedControl(
                            options = NexaPreferencesDataStore.HEARTBEAT_SCREEN_ON_OPTIONS.map { it to "${it}s" },
                            selected = uiState.heartbeatScreenOnSec,
                            onSelect = { sec ->
                                if (uiState.hapticEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                viewModel.onHeartbeatScreenOnSelected(sec)
                            },
                            emphasizeWithAccentFill = false,
                            compact = true
                        )
                    }

                    SettingsRowDivider(insetStart = false)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Heartbeat (screen off)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SettingsSegmentedControl(
                            options = NexaPreferencesDataStore.HEARTBEAT_SCREEN_OFF_OPTIONS.map { it to "${it}s" },
                            selected = uiState.heartbeatScreenOffSec,
                            onSelect = { sec ->
                                if (uiState.hapticEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                viewModel.onHeartbeatScreenOffSelected(sec)
                            },
                            emphasizeWithAccentFill = false,
                            compact = true
                        )
                    }

                    SettingsRowDivider(insetStart = false)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Terminal font size",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${uiState.terminalFontSizeSp} sp",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = uiState.terminalFontSizeSp.toFloat(),
                            onValueChange = { viewModel.onTerminalFontSizePreview(it.roundToInt()) },
                            onValueChangeFinished = { viewModel.onTerminalFontSizeCommitted() },
                            valueRange = NexaPreferencesDataStore.MIN_TERMINAL_FONT_SP.toFloat()..
                                NexaPreferencesDataStore.MAX_TERMINAL_FONT_SP.toFloat(),
                            steps = (NexaPreferencesDataStore.MAX_TERMINAL_FONT_SP -
                                NexaPreferencesDataStore.MIN_TERMINAL_FONT_SP) - 1,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = nexaColors.primaryButtonFill,
                                inactiveTrackColor = nexaColors.elevatedSurface,
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            )
                        )

                        Surface(
                            color = if (nexaColors.isDark) Color(0xFF070B10) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "nexa@mobile:~\$ ack packet_seq=10492 OK",
                                fontFamily = FontFamily.Monospace,
                                fontSize = uiState.terminalFontSizeSp.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showSecuritySheet) {
        DeviceSecurityDetailSheet(
            uiState = uiState,
            enrolledDateText = enrolledDateText,
            statusLabel = identityBadgeLabel,
            statusColor = identityStatusColor,
            onCopyDeviceId = {
                copyToClipboard(context, "NEXA Device ID", uiState.deviceName)
                viewModel.setStatusNotice("Device ID copied.")
            },
            onCopyFingerprint = {
                copyToClipboard(context, "NEXA SHA-256 Fingerprint", uiState.keySnapshot.sha256ColonHex)
                viewModel.setStatusNotice("SHA-256 fingerprint copied.")
            },
            onRegenerateKey = { viewModel.regenerateHardwareKey() },
            onOpenEnrollDialog = {
                viewModel.clearEnrollError()
                showEnrollDialog = true
            },
            onDismiss = { showSecuritySheet = false }
        )
    }

    if (showEnrollDialog) {
        EnrollmentCliDialog(
            uiState = uiState,
            enrollCodeInput = enrollCodeInput,
            onCodeChanged = { enrollCodeInput = it },
            onCopyCommand = { cmd ->
                copyToClipboard(context, "NEXA Enroll CLI", cmd)
                viewModel.setStatusNotice("CLI command copied.")
            },
            onSubmit = { viewModel.submitEnrollmentCode(enrollCodeInput) },
            onDismiss = {
                viewModel.clearEnrollError()
                showEnrollDialog = false
            }
        )
    }
}

@Composable
private fun TestConnectionRow(
    state: ConnectionTestState,
    onTest: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val isTesting = state is ConnectionTestState.Testing

    val leadingTint = when (state) {
        is ConnectionTestState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionTestState.Testing -> MaterialTheme.colorScheme.primary
        is ConnectionTestState.Success -> nexaColors.emerald
        is ConnectionTestState.Failed -> nexaColors.crimson
    }
    val subtitleText = when (state) {
        is ConnectionTestState.Idle -> "Verify server reachability and latency"
        is ConnectionTestState.Testing -> state.detail
        is ConnectionTestState.Success -> state.detail
        is ConnectionTestState.Failed -> state.detail
    }
    val subtitleColor = when (state) {
        is ConnectionTestState.Failed -> nexaColors.crimson
        is ConnectionTestState.Success -> nexaColors.emerald
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SettingsListRow(
        leadingIcon = Icons.Default.Refresh,
        leadingTint = leadingTint,
        title = "Test connection",
        subtitle = subtitleText,
        subtitleColor = subtitleColor,
        showChevron = false,
        onClick = if (isTesting) null else onTest,
        trailing = {
            when (state) {
                is ConnectionTestState.Idle -> {
                    Text(
                        text = "Run",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is ConnectionTestState.Testing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is ConnectionTestState.Success -> {
                    Text(
                        text = "${state.latencyMs} ms",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = nexaColors.emerald
                    )
                }
                is ConnectionTestState.Failed -> {
                    Text(
                        text = state.badge,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = nexaColors.crimson
                    )
                }
            }
        }
    )
}

private fun resolveAudioDisplayName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Custom tone" } ?: "Custom tone"
    } catch (_: Exception) {
        "Custom tone"
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
