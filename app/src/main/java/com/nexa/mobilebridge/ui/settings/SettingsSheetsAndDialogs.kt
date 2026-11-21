package com.nexa.mobilebridge.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.mobilebridge.core.security.KeystoreKeyState
import com.nexa.mobilebridge.theme.LocalNexaColors

/**
 * Clean detail bottom sheet for Hardware Identity & full 32-octet SHA-256 public key fingerprint.
 * Keeps the main Settings screen uncluttered while preserving full technical verification & copy actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceSecurityDetailSheet(
    uiState: SettingsUiState,
    enrolledDateText: String,
    statusLabel: String,
    statusColor: Color,
    onCopyDeviceId: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onRegenerateKey: () -> Unit,
    onOpenEnrollDialog: () -> Unit,
    onDismiss: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isInvalidated = uiState.keySnapshot.state == KeystoreKeyState.INVALIDATED ||
        uiState.enrollmentStatus == "INVALIDATED"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kunci & Identitas Perangkat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = enrolledDateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusIndicatorDot(color = statusColor)
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }

            if (isInvalidated) {
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
                                text = "Kunci Keystore Gugur",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = nexaColors.crimson
                            )
                        }
                        Text(
                            text = "Sidik jari sistem Android berubah. Buat ulang kunci P-256 di perangkat ini lalu jalankan nexa-enroll re-pair di server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(
                            onClick = onRegenerateKey,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = nexaColors.crimson,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Buat Ulang Kunci Hardware", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Grouped key metadata
            SettingsGroupCard {
                SettingsListRow(
                    title = "ID Perangkat",
                    subtitle = uiState.deviceName,
                    subtitleMonospace = true,
                    trailing = {
                        IconButton(
                            onClick = onCopyDeviceId,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Salin ID perangkat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
                SettingsRowDivider(insetStart = false)
                SettingsListRow(
                    leadingIcon = Icons.Default.Memory,
                    title = "Penyimpanan Kunci",
                    subtitle = "Android Keystore · ${uiState.keySnapshot.securityLevelLabel}"
                )
                SettingsRowDivider(insetStart = true)
                SettingsListRow(
                    leadingIcon = Icons.Default.Fingerprint,
                    title = "Otentikasi",
                    subtitle = "Sidik jari per penggunaan (BIOMETRIC_STRONG)"
                )
            }

            // Full 32-octet SHA-256 fingerprint block
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Sidik jari publik (SHA-256)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.keySnapshot.sha256ColonHex.isNotBlank()) {
                        TextButton(
                            onClick = onCopyFingerprint,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Salin", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Surface(
                    color = if (nexaColors.isDark) Color(0xFF070B10) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, nexaColors.cardBorder.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.keySnapshot.sha256Lines4x8.isNotBlank()) {
                        Text(
                            text = uiState.keySnapshot.sha256Lines4x8,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(14.dp)
                        )
                    } else {
                        Text(
                            text = uiState.keySnapshot.errorMessage
                                ?: "Kunci hardware belum tersedia. Pastikan sidik jari aktif di Android.",
                            style = MaterialTheme.typography.bodySmall,
                            color = nexaColors.amber,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    onDismiss()
                    onOpenEnrollDialog()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, nexaColors.cardBorder)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.enrollmentStatus == "ACTIVE") {
                        "Panduan Daftar Ulang (nexa-enroll re-pair)"
                    } else {
                        "Daftarkan Perangkat via Kode CLI"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * CLI Enrollment / Re-Pair Dialog (FR-AUTH-01 & FR-AUTH-03 / Audit A7) with one-tap command copy.
 */
@Composable
internal fun EnrollmentCliDialog(
    uiState: SettingsUiState,
    enrollCodeInput: String,
    onCodeChanged: (String) -> Unit,
    onCopyCommand: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val cliSubcommand = if (uiState.enrollmentStatus == "ACTIVE") "re-pair" else "start"
    val cliCommand = "node scripts/nexa-enroll.js $cliSubcommand --device ${uiState.deviceName}"

    AlertDialog(
        onDismissRequest = { if (!uiState.isEnrolling) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (uiState.enrollmentStatus == "ACTIVE") {
                    "Daftar Ulang Perangkat (re-pair)"
                } else {
                    "Daftarkan Perangkat via CLI"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "1. Jalankan perintah berikut via SSH di server N.E.X.A:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = if (nexaColors.isDark) Color(0xFF070B10) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, nexaColors.cardBorder.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cliCommand,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onCopyCommand(cliCommand) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Salin perintah CLI",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "2. Masukkan kode sekali pakai 8 karakter (berlaku 5 menit):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = enrollCodeInput,
                    onValueChange = { onCodeChanged(it.uppercase().take(9)) },
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
                onClick = onSubmit
            ) {
                Text("Kirim & Daftarkan", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !uiState.isEnrolling,
                onClick = onDismiss
            ) {
                Text("Tutup")
            }
        }
    )
}
