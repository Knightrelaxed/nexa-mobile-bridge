package com.nexa.mobilebridge.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobilebridge.core.security.BiometricAuthHelper
import com.nexa.mobilebridge.core.security.KeystoreKeyState
import com.nexa.mobilebridge.core.security.SecureScreenEffect
import com.nexa.mobilebridge.core.security.findFragmentActivity
import com.nexa.mobilebridge.data.local.AccentPreset
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.data.local.ThemeMode
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings & Security screen matching Stitch Blueprint v3 (05_settings_security_v3.png)
 * and Audit A7 / FR-AUTH-01..03 / FR-SEC-06:
 * - FLAG_SECURE active on entire screen via ref-counted SecureScreenEffect()
 * - Full 32-octet SHA-256 fingerprint (4 lines x 8 octets) + Copy button
 * - CLI Enrollment / Re-Pair dialog + KeyPermanentlyInvalidatedException recovery
 * - Domain locked post-enrollment behind BIOMETRIC_STRONG
 * - Token 10-second timed reveal behind BIOMETRIC_STRONG
 * - Preferences auto-save: TTS, Haptic, Terminal Font 8-16sp, Gelap OLED/Terang/Sistem, 4 Accent Swatches
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    // FR-SEC-06 / Audit A7: Protect Settings screen against screenshots and recents preview
    SecureScreenEffect()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val nexaColors = LocalNexaColors.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    var showEnrollDialog by remember { mutableStateOf(false) }
    var enrollCodeInput by remember { mutableStateOf("") }
    var activePreviewRingtone by remember { mutableStateOf<Ringtone?>(null) }
    var isPlayingSoundPreview by remember { mutableStateOf(false) }

    // RD-M5: Automatically revert Stop icon back to Play once notification tone finishes playing
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

    // U-M6: Auto-close enrollment dialog ONLY when enrollment succeeds (preserve code & show error on failure)
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

    // FR-CHAT-15 / 05_settings_security_v3.png: SAF custom audio picker for Chat notification tone
    val soundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val displayName = resolveAudioDisplayName(context, uri)
            viewModel.onChatNotificationSoundSelected(uri.toString(), displayName)
            viewModel.setStatusNotice("Nada notifikasi chat diperbarui: $displayName")
        }
    }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("id-ID")) }
    val enrolledDateText = if (uiState.enrolledAtEpochSec > 0L) {
        "Terdaftar: ${dateFormatter.format(Date(uiState.enrolledAtEpochSec * 1000L))}"
    } else {
        "Belum terdaftar di server"
    }

    val (identityPillText, identityPillColor) = when {
        uiState.keySnapshot.state == KeystoreKeyState.INVALIDATED || uiState.enrollmentStatus == "INVALIDATED" ->
            "GUGUR" to nexaColors.crimson
        uiState.enrollmentStatus == "ACTIVE" ->
            "AKTIF · P-256" to nexaColors.emerald
        else ->
            "BELUM DAFTAR" to nexaColors.amber
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Pengaturan & Keamanan",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Layar terlindungi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                                contentDescription = "Kembali"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                HorizontalDivider(color = nexaColors.cardBorder.copy(alpha = 0.7f))
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!uiState.statusNotice.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = uiState.statusNotice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearStatusNotice() }) {
                            Text("Tutup")
                        }
                    }
                }
            }

            // =================================================================
            // CARD 1: IDENTITAS & KEYSTORE (Gaya v3 - 36 dp Emerald Badge)
            // =================================================================
            SettingsSectionCard(
                badgeIcon = Icons.Default.Shield,
                badgeTint = nexaColors.emerald,
                title = "IDENTITAS & KEYSTORE",
                subtitle = "Hardware-backed cryptographic enclave",
                statusPillText = identityPillText,
                statusPillColor = identityPillColor
            ) {
                // KeyPermanentlyInvalidatedException Recovery Banner (FR-AUTH-03 / Audit A7)
                if (uiState.keySnapshot.state == KeystoreKeyState.INVALIDATED ||
                    uiState.enrollmentStatus == "INVALIDATED"
                ) {
                    Surface(
                        color = nexaColors.crimson.copy(alpha = if (nexaColors.isDark) 0.14f else 0.10f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, nexaColors.crimson.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
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
                                    text = "Kunci Keystore Gugur (Sidik Jari OS Berubah)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = nexaColors.crimson
                                )
                            }
                            Text(
                                text = "Buat ulang pasangan kunci P-256 baru di perangkat ini, lalu jalankan `node scripts/nexa-enroll.js re-pair --device ${uiState.deviceName}` di server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Button(
                                onClick = { viewModel.regenerateHardwareKey() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = nexaColors.crimson,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("Buat Ulang Kunci Hardware")
                            }
                        }
                    }
                }

                // ID Perangkat + Copy button
                Surface(
                    color = MaterialTheme.colorScheme.background.copy(alpha = if (nexaColors.isDark) 0.55f else 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, nexaColors.cardBorder.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ID PERANGKAT",
                                style = NexaMonoTypography.badge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = uiState.deviceName,
                                style = NexaMonoTypography.metricMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = enrolledDateText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                copyToClipboard(context, "NEXA Device ID", uiState.deviceName)
                                viewModel.setStatusNotice("ID perangkat disalin.")
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Salin ID Perangkat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                InfoFieldBox(
                    label = "PENYIMPANAN KUNCI",
                    value = "Android Keystore · ${uiState.keySnapshot.securityLevelLabel}",
                    leadingIcon = Icons.Default.Memory,
                    leadingTint = MaterialTheme.colorScheme.primary
                )

                InfoFieldBox(
                    label = "OTENTIKASI",
                    value = "Sidik Jari per Penggunaan (BIOMETRIC_STRONG)",
                    leadingIcon = Icons.Default.Fingerprint,
                    leadingTint = nexaColors.violet
                )

                // Full 32-octet SHA-256 Fingerprint (4 rows x 8 octets) + Salin button (Stitch v3 / Audit A7)
                Surface(
                    color = if (nexaColors.isDark) Color(0xFF070B10) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, nexaColors.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "SIDIK JARI KUNCI PUBLIK (SHA256)",
                                style = NexaMonoTypography.badge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (uiState.keySnapshot.sha256ColonHex.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        copyToClipboard(
                                            context,
                                            "NEXA SHA-256 Fingerprint",
                                            uiState.keySnapshot.sha256ColonHex
                                        )
                                        viewModel.setStatusNotice("Sidik jari SHA-256 (32 oktet) disalin.")
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Salin", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        if (uiState.keySnapshot.sha256Lines4x8.isNotBlank()) {
                            Text(
                                text = uiState.keySnapshot.sha256Lines4x8,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = uiState.keySnapshot.errorMessage
                                    ?: "Kunci hardware belum tersedia. Pastikan sidik jari aktif di Android.",
                                style = MaterialTheme.typography.bodySmall,
                                color = nexaColors.amber
                            )
                        }
                    }
                }

                // Panduan Daftar / Daftar Ulang (nexa-enroll re-pair) - opens CLI instruction & code dialog
                OutlinedButton(
                    onClick = {
                        viewModel.clearEnrollError()
                        showEnrollDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, nexaColors.cardBorder)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = nexaColors.amber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.enrollmentStatus == "ACTIVE") {
                            "Panduan Daftar Ulang (nexa-enroll re-pair)"
                        } else {
                            "Daftarkan Perangkat via Kode CLI (nexa-enroll)"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // =================================================================
            // CARD 2: KONEKSI SERVER (Gaya v3 - 36 dp Cyan Badge)
            // =================================================================
            SettingsSectionCard(
                badgeIcon = Icons.Default.Hub,
                badgeTint = nexaColors.cyan,
                title = "KONEKSI SERVER",
                subtitle = "WebSocket RPC & Auth pipeline",
                statusPillText = "TLS 1.3",
                statusPillColor = nexaColors.emerald
            ) {
                // Domain Server: locked read-only once enrolled, unlocked via BIOMETRIC_STRONG (Audit A7)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (uiState.isDomainUnlocked) "DOMAIN SERVER" else "DOMAIN SERVER (TERKUNCI)",
                            style = NexaMonoTypography.badge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!uiState.isDomainUnlocked) {
                            TextButton(
                                onClick = {
                                    val activity = context.findFragmentActivity()
                                    if (activity == null) {
                                        viewModel.setStatusNotice("Activity biometrik tidak tersedia.")
                                    } else {
                                        BiometricAuthHelper.authenticateGuard(
                                            activity = activity,
                                            title = "Buka Kunci Domain Server",
                                            subtitle = "Verifikasi sidik jari untuk mengubah URL server N.E.X.A",
                                            onSuccess = { viewModel.unlockDomainAfterBiometric() },
                                            onError = { err -> viewModel.setStatusNotice("Verifikasi dibatalkan: $err") }
                                        )
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ubah (Sidik Jari)")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = uiState.serverUrl,
                        onValueChange = { viewModel.onServerUrlChanged(it) },
                        enabled = uiState.isDomainUnlocked,
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.isDomainUnlocked) Icons.Default.Public else Icons.Default.Lock,
                                contentDescription = if (uiState.isDomainUnlocked) "Domain dapat diubah" else "Domain terkunci",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }

                // Token Jembatan (Bearer) with biometric-gated 10-second reveal (Audit A7)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val isTokenVisible = uiState.tokenRevealSecondsLeft > 0
                    OutlinedTextField(
                        value = uiState.authToken,
                        onValueChange = { viewModel.onAuthTokenChanged(it) },
                        label = { Text("Token Jembatan (Bearer)") },
                        singleLine = true,
                        visualTransformation = if (isTokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (isTokenVisible) {
                                        viewModel.hideTokenImmediately()
                                    } else {
                                        val activity = context.findFragmentActivity()
                                        if (activity == null) {
                                            viewModel.setStatusNotice("Activity biometrik tidak tersedia.")
                                        } else {
                                            BiometricAuthHelper.authenticateGuard(
                                                activity = activity,
                                                title = "Intip Token Jembatan",
                                                subtitle = "Token akan terlihat selama 10 detik saja",
                                                onSuccess = { viewModel.startTokenReveal10Seconds() },
                                                onError = { err -> viewModel.setStatusNotice("Ditolak: $err") }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isTokenVisible) "Sembunyikan Token" else "Intip Token 10 Detik"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (isTokenVisible) {
                            "Token terlihat sementara (${uiState.tokenRevealSecondsLeft} dtk tersisa)"
                        } else {
                            "Intip 10 dtk (wajib sidik jari)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isTokenVisible) nexaColors.amber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = uiState.hmacSecret,
                    onValueChange = { viewModel.onHmacSecretChanged(it) },
                    label = { Text("HMAC Secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                // 2-row Configurable Heartbeat controls (Stitch v3 / NFR-BAT-01 / U-H12)
                Text(
                    text = "INTERVAL HEARTBEAT WS-A (KETUK UNTUK UBAH)",
                    style = NexaMonoTypography.badge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HeartbeatConfigRow(
                    icon = Icons.Default.LightMode,
                    iconTint = MaterialTheme.colorScheme.primary,
                    label = "Layar Menyala",
                    value = "${uiState.heartbeatScreenOnSec} dtk",
                    onClick = {
                        if (uiState.hapticEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        viewModel.onCycleHeartbeatScreenOn()
                    }
                )
                HeartbeatConfigRow(
                    icon = Icons.Default.DarkMode,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Layar Mati",
                    value = "${uiState.heartbeatScreenOffSec} dtk",
                    onClick = {
                        if (uiState.hapticEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        viewModel.onCycleHeartbeatScreenOff()
                    }
                )

                // Live Terminal Kill Switch status (U-H12)
                Surface(
                    color = MaterialTheme.colorScheme.background.copy(alpha = if (nexaColors.isDark) 0.55f else 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, nexaColors.cardBorder.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Kill Switch Terminal Server",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = uiState.killSwitchSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        val ksTint = if (uiState.isKillSwitchActive) {
                            nexaColors.emerald
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(
                            color = if (uiState.isKillSwitchActive) {
                                nexaColors.emerald.copy(alpha = if (nexaColors.isDark) 0.16f else 0.12f)
                            } else {
                                nexaColors.elevatedSurface
                            },
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (uiState.isKillSwitchActive) nexaColors.emerald else nexaColors.cardBorder)
                        ) {
                            Text(
                                text = uiState.killSwitchLabel,
                                style = NexaMonoTypography.badge,
                                color = ksTint,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Action buttons row (RD-C2: compact horizontal padding so neither button truncates on 360 dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.testConnection() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, nexaColors.cardBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Uji Koneksi",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            if (uiState.hapticEnabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (uiState.enrollmentStatus == "ACTIVE") {
                                val activity = context.findFragmentActivity()
                                if (activity != null) {
                                    BiometricAuthHelper.authenticateGuard(
                                        activity = activity,
                                        title = "Terapkan Konfigurasi Server",
                                        subtitle = "Konfirmasi sidik jari untuk menyimpan pengaturan koneksi",
                                        onSuccess = { viewModel.saveSettings(onSaved = onNavigateBack) },
                                        onError = { err -> viewModel.setStatusNotice("Dibatalkan: $err") }
                                    )
                                } else {
                                    // U-H3: Fail-closed: NEVER bypass biometric verification
                                    viewModel.setStatusNotice("Biometrik diperlukan untuk menerapkan pengaturan. Buka ulang halaman ini.")
                                }
                            } else {
                                viewModel.saveSettings(onSaved = onNavigateBack)
                            }
                        },
                        modifier = Modifier
                            .weight(1.1f)
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = nexaColors.primaryButtonFill,
                            contentColor = nexaColors.onPrimaryButtonFill
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        if (uiState.enrollmentStatus == "ACTIVE") {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        Text(
                            text = if (uiState.enrollmentStatus == "ACTIVE") "Terapkan (Sidik Jari)" else "Simpan Koneksi",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // =================================================================
            // CARD 3: PREFERENSI (TERSIMPAN OTOMATIS) (Gaya v3 - 36 dp Violet Badge)
            // =================================================================
            SettingsSectionCard(
                badgeIcon = Icons.Default.Tune,
                badgeTint = nexaColors.violet,
                title = "PREFERENSI (TERSIMPAN OTOMATIS)",
                subtitle = "Audio, haptik, font terminal, dan tampilan"
            ) {
                PreferenceToggleRow(
                    title = "Suara Baca Lokal",
                    badge = "TTS Android · id-ID",
                    subtitle = "Bacakan balasan AI secara offline",
                    checked = uiState.ttsEnabled,
                    onCheckedChange = { viewModel.onTtsToggled(it) }
                )

                HorizontalDivider(color = nexaColors.cardBorder.copy(alpha = 0.5f))

                PreferenceToggleRow(
                    title = "Getaran Haptik",
                    badge = if (uiState.hapticEnabled) "AKTIF" else "MATI",
                    subtitle = "Umpan balik sentuh pada tombol utama",
                    checked = uiState.hapticEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.onHapticToggled(enabled)
                        if (enabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                )

                HorizontalDivider(color = nexaColors.cardBorder.copy(alpha = 0.5f))

                // FR-CHAT-15 / 05_settings_security_v3.png / U-H12: Chat Notification Tone Picker + Preview
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "NADA NOTIFIKASI CHAT",
                            style = NexaMonoTypography.badge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.chatNotificationSoundUri.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    runCatching { activePreviewRingtone?.stop() }
                                    activePreviewRingtone = null
                                    isPlayingSoundPreview = false
                                    viewModel.onChatNotificationSoundSelected("", "Default Sistem")
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Reset Default", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.background.copy(alpha = if (nexaColors.isDark) 0.55f else 0.7f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, nexaColors.cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    soundPickerLauncher.launch(arrayOf("audio/*"))
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Audiotrack,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = uiState.chatNotificationSoundLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Pilih",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Surface(
                            color = nexaColors.elevatedSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, nexaColors.cardBorder),
                            modifier = Modifier
                                .size(48.dp)
                                .clickable(role = Role.Button) {
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
                                            viewModel.setStatusNotice("Gagal memutar pratinjau nada notifikasi.")
                                        }
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlayingSoundPreview) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlayingSoundPreview) "Hentikan pratinjau nada" else "Putar pratinjau nada",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = nexaColors.cardBorder.copy(alpha = 0.5f))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "UKURAN FONT TERMINAL",
                            style = NexaMonoTypography.badge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${uiState.terminalFontSizeSp}sp",
                                style = NexaMonoTypography.badge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
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
                            activeTickColor = nexaColors.onPrimaryButtonFill,
                            inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${NexaPreferencesDataStore.MIN_TERMINAL_FONT_SP}sp",
                            style = NexaMonoTypography.codeLine,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${NexaPreferencesDataStore.MAX_TERMINAL_FONT_SP}sp",
                            style = NexaMonoTypography.codeLine,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        color = if (nexaColors.isDark) Color(0xFF070B10) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, nexaColors.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "nexa@mobile:~\$ ack packet_seq=10492 OK",
                            fontFamily = FontFamily.Monospace,
                            fontSize = uiState.terminalFontSizeSp.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }

                HorizontalDivider(color = nexaColors.cardBorder.copy(alpha = 0.5f))

                Text(
                    text = "MODE TAMPILAN",
                    style = NexaMonoTypography.badge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // U-H13 / 05_settings_security_v3.png: Clean 2-button [Gelap OLED] | [Terang] row + system toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val primaryModes = listOf(
                        Triple(ThemeMode.DARK, "Gelap OLED", Icons.Default.DarkMode),
                        Triple(ThemeMode.LIGHT, "Terang", Icons.Default.LightMode)
                    )
                    primaryModes.forEach { (mode, label, icon) ->
                        val selected = uiState.themeMode == mode
                        if (selected) {
                            Button(
                                onClick = { viewModel.onThemeModeSelected(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = nexaColors.primaryButtonFill,
                                    contentColor = nexaColors.onPrimaryButtonFill
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.onThemeModeSelected(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, nexaColors.cardBorder),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Ikuti tema sistem Android otomatis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = uiState.themeMode == ThemeMode.SYSTEM,
                        onCheckedChange = { followSystem ->
                            viewModel.onThemeModeSelected(if (followSystem) ThemeMode.SYSTEM else ThemeMode.DARK)
                        }
                    )
                }

                HorizontalDivider(color = nexaColors.cardBorder.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WARNA AKSEN",
                        style = NexaMonoTypography.badge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.accentPreset.badgeName,
                        style = NexaMonoTypography.badge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AccentPreset.entries.forEach { preset ->
                        val selected = uiState.accentPreset == preset
                        val swatchColor = nexaColors.swatchFor(preset, selected = selected)
                        val checkColor = if (nexaColors.isDark) Color(0xFF021014) else Color.White

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.RadioButton) {
                                    if (uiState.hapticEnabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    viewModel.onAccentPresetSelected(preset)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(swatchColor)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onBackground else nexaColors.cardBorder,
                                        shape = RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Aktif",
                                        tint = checkColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (selected) "\u2713 ${preset.displayName}" else preset.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    // CLI Enrollment / Re-Pair Dialog (FR-AUTH-01 & FR-AUTH-03 / Audit A7)
    if (showEnrollDialog) {
        val cliSubcommand = if (uiState.enrollmentStatus == "ACTIVE") "re-pair" else "start"
        val cliCommand = "node scripts/nexa-enroll.js $cliSubcommand --device ${uiState.deviceName}"

        AlertDialog(
            onDismissRequest = { if (!uiState.isEnrolling) showEnrollDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = if (uiState.enrollmentStatus == "ACTIVE") {
                        "Daftar Ulang Perangkat (re-pair)"
                    } else {
                        "Daftarkan Perangkat via CLI"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "1. Jalankan perintah berikut via SSH pada VPS server N.E.X.A:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = if (nexaColors.isDark) Color(0xFF070B10) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, nexaColors.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = cliCommand,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Text(
                        text = "2. Masukkan kode sekali pakai 8 karakter (berlaku 5 menit):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = enrollCodeInput,
                        onValueChange = { enrollCodeInput = it.uppercase().take(9) },
                        label = { Text("Kode Enrollment (contoh: ABCD-2345)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (uiState.isEnrolling) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Mengirim kunci publik ke server...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!uiState.enrollError.isNullOrBlank()) {
                        Text(
                            text = uiState.enrollError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = nexaColors.crimson
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !uiState.isEnrolling && enrollCodeInput.replace("-", "").length == 8,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nexaColors.primaryButtonFill,
                        contentColor = nexaColors.onPrimaryButtonFill
                    ),
                    onClick = {
                        // U-M6: Keep dialog open while enrolling; auto-closes only on success
                        viewModel.submitEnrollmentCode(enrollCodeInput)
                    }
                ) {
                    Text("Kirim & Daftarkan", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !uiState.isEnrolling,
                    onClick = {
                        viewModel.clearEnrollError()
                        showEnrollDialog = false
                    }
                ) {
                    Text("Tutup")
                }
            }
        )
    }
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
        uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Audio Kustom" } ?: "Audio Kustom"
    } catch (_: Exception) {
        "Audio Kustom"
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
private fun SettingsSectionCard(
    badgeIcon: ImageVector,
    badgeTint: Color,
    title: String,
    subtitle: String,
    statusPillText: String? = null,
    statusPillColor: Color = badgeTint,
    content: @Composable () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, nexaColors.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = badgeTint.copy(alpha = if (nexaColors.isDark) 0.16f else 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, badgeTint.copy(alpha = 0.4f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = badgeIcon,
                            contentDescription = null,
                            tint = badgeTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = NexaMonoTypography.badge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (statusPillText != null) {
                    Surface(
                        color = statusPillColor.copy(alpha = if (nexaColors.isDark) 0.15f else 0.10f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, statusPillColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = statusPillText,
                            style = NexaMonoTypography.badge,
                            color = statusPillColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            content()
        }
    }
}

@Composable
private fun InfoFieldBox(
    label: String,
    value: String,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = MaterialTheme.colorScheme.primary
) {
    val nexaColors = LocalNexaColors.current
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = if (nexaColors.isDark) 0.55f else 0.7f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, nexaColors.cardBorder.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = NexaMonoTypography.badge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = leadingTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun HeartbeatConfigRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = if (nexaColors.isDark) 0.55f else 0.7f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, nexaColors.cardBorder.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Surface(
                color = nexaColors.elevatedSurface,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, nexaColors.cardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = value,
                        style = NexaMonoTypography.badge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.UnfoldMore,
                        contentDescription = "Ubah interval",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceToggleRow(
    title: String,
    badge: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val nexaColors = LocalNexaColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    color = nexaColors.elevatedSurface,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = badge,
                        style = NexaMonoTypography.badge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
