package com.nexa.mobilebridge.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.mobilebridge.core.security.SecureScreenEffect
import com.nexa.mobilebridge.data.chat.PendingFinanceStepUp
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

/**
 * Hardware-Biometric Finance Step-Up Modal Bottom Sheet matching Stitch Blueprint v2 (01_chat_ai_native_v2.png)
 * and PRD FR-AUTH-07, FR-AUTH-08, FR-SEC-06:
 * - Ref-counted FLAG_SECURE active while visible
 * - Auto-dismisses virtual keyboard (IME) so all 5 rows + 2 buttons never scroll off screen
 * - Amber security banner with 1 Hz countdown timer ("Sisa XX dtk") and "Layar terlindungi"
 * - 5 full canonical detail rows from server without ellipsis on recipient name
 * - 48 dp touch targets for "Konfirmasi dengan sidik jari" (#00B8D4) and "Batalkan" (WCAG AA >= 4.5:1)
 */
@Composable
fun FinanceStepUpBottomSheet(
    stepUp: PendingFinanceStepUp,
    onConfirmBiometric: (PendingFinanceStepUp) -> Unit,
    onCancel: (String) -> Unit,
    onExpired: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // FR-SEC-06: Enforce ref-counted FLAG_SECURE while Finance Step-Up modal is visible
    SecureScreenEffect()

    val nexaColors = LocalNexaColors.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var secondsLeft by remember(stepUp.actionId, stepUp.expiresAtEpochMs) {
        val initial = ceil((stepUp.expiresAtEpochMs - System.currentTimeMillis()) / 1000.0).toInt()
        mutableIntStateOf(max(1, initial))
    }

    val latestIsSubmitting by rememberUpdatedState(stepUp.isSubmitting)

    LaunchedEffect(stepUp.actionId, stepUp.expiresAtEpochMs) {
        // FR-AUTH-07: Automatically close IME keyboard so recipient and nominal are never obscured
        focusManager.clearFocus(force = true)
        keyboardController?.hide()

        while (true) {
            val remainingMs = stepUp.expiresAtEpochMs - System.currentTimeMillis()
            val sec = ceil(remainingMs / 1000.0).toInt()
            if (sec <= 0) {
                secondsLeft = 0
                if (!latestIsSubmitting) {
                    onExpired(stepUp.actionId)
                    break
                }
                delay(500L)
                continue
            }
            secondsLeft = sec
            delay(1_000L)
        }
    }

    val sheetBg = if (nexaColors.isDark) Color(0xFF111720) else MaterialTheme.colorScheme.surface
    val tableBg = if (nexaColors.isDark) Color(0xFF0C1118) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val amberBannerBg = if (nexaColors.isDark) Color(0xFF241A0E) else Color(0xFFFFF3E0)
    val amberText = nexaColors.amber

    Surface(
        color = sheetBg,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.2.dp, nexaColors.amber.copy(alpha = 0.7f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top handle indicator pill
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(
                            color = nexaColors.cardBorder,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }

            // 1. Clean 2-Row Amber Security Banner (prevents cramping on 411 dp, U-H7)
            Surface(
                color = amberBannerBg,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, nexaColors.amber.copy(alpha = 0.85f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Approval warning",
                                tint = amberText,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Fingerprint approval required",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = amberText
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "${secondsLeft}s left",
                            style = NexaMonoTypography.badge,
                            fontWeight = FontWeight.Bold,
                            color = amberText
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Screen protected",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Screen protected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. Canonical 5-Row Transaction Detail Table (No Ellipsis on Recipient!)
            Surface(
                color = tableBg,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, nexaColors.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Row 1: Action + ID
                    StepUpDetailRow(
                        label = "Action",
                        value = "${stepUp.actionLabel} (ID: #${stepUp.actionId})",
                        isMono = true
                    )
                    HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder.copy(alpha = 0.5f))

                    // Row 2: Amount
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Amount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stepUp.formattedAmount,
                            style = NexaMonoTypography.metricMedium.copy(fontSize = 15.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder.copy(alpha = 0.5f))

                    // Row 3: Recipient (Full Name Without Ellipsis - FR-AUTH-07)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "Recipient",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = stepUp.recipientFull,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            softWrap = true
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder.copy(alpha = 0.5f))

                    // Row 4: Destination + Whitelist Status
                    val wlTag = if (stepUp.whitelisted) "Verified" else "Unverified"
                    StepUpDetailRow(
                        label = "Destination",
                        value = "${stepUp.bank} •••• ${stepUp.acctLast4} ($wlTag)",
                        isMono = true,
                        valueColor = if (stepUp.whitelisted) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            amberText
                        }
                    )
                    HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder.copy(alpha = 0.5f))

                    // Row 5: Daily Limit Left
                    StepUpDetailRow(
                        label = "Daily limit left",
                        value = stepUp.formattedDailyRemaining,
                        isMono = true
                    )
                }
            }

            if (!stepUp.errorBanner.isNullOrBlank()) {
                Text(
                    text = stepUp.errorBanner,
                    style = MaterialTheme.typography.bodySmall,
                    color = nexaColors.crimson,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // 3. Primary Biometric Confirmation Button
            Button(
                onClick = { onConfirmBiometric(stepUp) },
                enabled = !stepUp.isSubmitting && secondsLeft > 0,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = nexaColors.primaryButtonFill,
                    contentColor = nexaColors.onPrimaryButtonFill
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (stepUp.isSubmitting) {
                        "Verifying..."
                    } else {
                        "Approve with fingerprint"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // 4. Secondary Cancel Button
            OutlinedButton(
                onClick = { onCancel(stepUp.actionId) },
                enabled = !stepUp.isSubmitting,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, nexaColors.cardBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (nexaColors.isDark) Color(0xFF1A2232) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StepUpDetailRow(
    label: String,
    value: String,
    isMono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            text = value,
            style = if (isMono) NexaMonoTypography.codeLine else MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}
