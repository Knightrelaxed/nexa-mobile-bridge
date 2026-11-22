package com.nexa.mobilebridge.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.mobilebridge.data.chat.CardActionItem
import com.nexa.mobilebridge.data.chat.InteractiveCardData
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Native Interactive Card docked inside N.E.X.A assistant chat bubbles (Phase 1).
 * Supports: FINANCE_TX, RECURRING_BILL, IDENTITY_PROPOSAL, MEMORY_HYGIENE, DISCIPLINE_CHECK.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NexaInteractiveCard(
    card: InteractiveCardData,
    isSubmitting: Boolean,
    errorMessage: String?,
    hapticEnabled: Boolean,
    onSubmitAction: (cardId: String, cardType: String, actionId: String, payload: JSONObject?) -> Unit,
    onOpenCategoryPicker: (InteractiveCardData) -> Unit,
    onOpenFinanceEdit: (InteractiveCardData) -> Unit,
    onOpenAccountPicker: (InteractiveCardData) -> Unit = {},
    onOpenMethodPicker: (InteractiveCardData) -> Unit = {},
    onOpenVaultEdit: (InteractiveCardData) -> Unit = {},
    onQuickPrompt: (String) -> Unit = {},
    framed: Boolean = true,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val haptic = LocalHapticFeedback.current

    fun performTapHaptic() {
        if (hapticEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Live 1 Hz countdown for expiring cards (e.g., Finance 5m auto-save or Discipline escalation)
    var remainingSec by remember(card.cardId, card.expiresAtMs, card.status) {
        val initial = if (card.isPending && card.expiresAtMs > 0L) {
            ((card.expiresAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        mutableLongStateOf(initial)
    }
    var isLocallyExpired by remember(card.cardId, card.expiresAtMs, card.status) {
        mutableStateOf(
            card.isPending && card.expiresAtMs > 0L && System.currentTimeMillis() > card.expiresAtMs + 3_000L
        )
    }
    LaunchedEffect(card.cardId, card.expiresAtMs, card.status) {
        if (!card.isPending || card.expiresAtMs <= 0L) return@LaunchedEffect
        while (true) {
            val sec = ((card.expiresAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            remainingSec = sec
            if (sec <= 0L) {
                delay(3_000L)
                if (card.isPending) {
                    isLocallyExpired = true
                }
                break
            }
            delay(1_000L)
        }
    }
    val effectiveIsPending = card.isPending && !isLocallyExpired

    // Dedicated ultra-clean Receipt layout for FINANCE_TX (zero nested box clutter, direct-tap rows, 2-button footer)
    if (card.cardType == "FINANCE_TX") {
        FinanceTxReceiptCard(
            card = card,
            effectiveIsPending = effectiveIsPending,
            isLocallyExpired = isLocallyExpired,
            remainingSec = remainingSec,
            isSubmitting = isSubmitting,
            errorMessage = errorMessage,
            framed = framed,
            onTapHaptic = { performTapHaptic() },
            onSubmitAction = onSubmitAction,
            onOpenCategoryPicker = onOpenCategoryPicker,
            onOpenAccountPicker = onOpenAccountPicker,
            onOpenMethodPicker = onOpenMethodPicker,
            onOpenFinanceEdit = onOpenFinanceEdit,
            modifier = modifier
        )
        return
    }

    // Local selection state for multi-select cards (MEMORY_HYGIENE)
    val selectionMap = remember(card.cardId, card.selectableItems) {
        mutableStateMapOf<String, Boolean>().apply {
            card.selectableItems.forEach { put(it.id, it.selected) }
        }
    }

    // Optional rejection reason inline input (IDENTITY_PROPOSAL)
    var showRejectReasonInput by remember(card.cardId) { mutableStateOf(false) }
    var rejectReasonText by remember(card.cardId) { mutableStateOf("") }

    val accentColor = when (card.accent) {
        "EMERALD" -> nexaColors.emerald
        "AMBER" -> nexaColors.amber
        "CRIMSON" -> nexaColors.crimson
        else -> MaterialTheme.colorScheme.primary
    }

    val cardBg = if (nexaColors.isDark) {
        Color(0xFF0B111A)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    val outerColor = if (framed) cardBg else Color.Transparent
    val outerBorder = if (framed) {
        BorderStroke(
            width = 1.dp,
            color = if (effectiveIsPending) accentColor.copy(alpha = 0.35f) else nexaColors.cardBorder.copy(alpha = 0.6f)
        )
    } else {
        null
    }

    Surface(
        color = outerColor,
        shape = RoundedCornerShape(12.dp),
        border = outerBorder,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (framed) 12.dp else 2.dp,
                    vertical = if (framed) 10.dp else 2.dp
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Header: Title + Subtitle + Status/Badge Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (card.subtitle.isNotEmpty()) {
                        Text(
                            text = card.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (card.badge.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = accentColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = card.badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // 2. Hero Value (e.g., -Rp 45.000) + Live Countdown Strip
            if (card.heroValue.isNotEmpty() || (effectiveIsPending && card.expiresAtMs > 0L)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (card.heroValue.isNotEmpty()) {
                        val heroColor = when (card.heroTone) {
                            "INCOME" -> nexaColors.emerald
                            "EXPENSE" -> MaterialTheme.colorScheme.onSurface
                            "TRANSFER" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = card.heroValue,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = heroColor
                        )
                    }

                    if (effectiveIsPending && card.expiresAtMs > 0L) {
                        val mins = remainingSec / 60L
                        val secs = remainingSec % 60L
                        val timerLabel = if (remainingSec > 0L) {
                            val prefix = card.expiresLabel.ifEmpty { "Expires in" }
                            "$prefix ${String.format(Locale.US, "%02d:%02d", mins, secs)}"
                        } else {
                            "Finalizing..."
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = nexaColors.amber,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = timerLabel,
                                style = NexaMonoTypography.codeLine.copy(fontSize = 11.sp),
                                color = nexaColors.amber,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // 3. Confidence Progress Bar (IDENTITY_PROPOSAL)
            if (card.confidence != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Confidence",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${card.confidence}%",
                            style = NexaMonoTypography.codeLine.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (card.confidence / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = accentColor,
                        trackColor = nexaColors.elevatedSurface
                    )
                }
            }

            // 4. Visual Diff Block (IDENTITY_PROPOSAL)
            if (card.diff != null && (card.diff.oldValue.isNotEmpty() || card.diff.newValue.isNotEmpty())) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.75.dp, nexaColors.cardBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (card.diff.oldValue.isNotEmpty()) {
                            Text(
                                text = card.diff.oldValue,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (card.diff.newValue.isNotEmpty()) {
                            Text(
                                text = card.diff.newValue,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // 5. Key-Value Metadata Fields
            if (card.fields.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.fields.forEach { field ->
                        val fieldValueColor = when (field.tone.uppercase()) {
                            "AMBER" -> nexaColors.amber
                            "EMERALD" -> nexaColors.emerald
                            "CRIMSON" -> nexaColors.crimson
                            "PRIMARY" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = field.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Text(
                                text = field.value,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (field.tone == "AMBER") FontWeight.SemiBold else FontWeight.Medium,
                                color = fieldValueColor,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 5b. Telegram-parity Proactive Clarification Banner when missing info (account/method/note)
            if (effectiveIsPending && card.promptText.isNotEmpty()) {
                Surface(
                    color = nexaColors.amber.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.75.dp, nexaColors.amber.copy(alpha = 0.40f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = card.promptText,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            // 6. Multi-Select Checklist Items (MEMORY_HYGIENE)
            if (card.selectableItems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.selectableItems.forEach { item ->
                        val checked = selectionMap[item.id] ?: item.selected
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = effectiveIsPending && !isSubmitting) {
                                    performTapHaptic()
                                    selectionMap[item.id] = !checked
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = effectiveIsPending && !isSubmitting,
                                    onCheckedChange = { next ->
                                        performTapHaptic()
                                        selectionMap[item.id] = next
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.size(32.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.meta.isNotEmpty()) {
                                        Text(
                                            text = item.meta,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 7. Inline Error Notice (transient network/action error)
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = nexaColors.crimson
                )
            }

            // 8. Footer: Active Buttons OR Locked Resolved Banner
            if (effectiveIsPending) {
                HorizontalDivider(
                    thickness = 0.75.dp,
                    color = nexaColors.cardBorder.copy(alpha = 0.5f)
                )

                // Optional inline rejection reason prompt for IDENTITY_PROPOSAL
                AnimatedVisibility(visible = showRejectReasonInput) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = rejectReasonText,
                            onValueChange = { rejectReasonText = it },
                            label = { Text("Reason (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { showRejectReasonInput = false },
                                enabled = !isSubmitting,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Back", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = {
                                    performTapHaptic()
                                    val payload = JSONObject().apply {
                                        if (rejectReasonText.isNotBlank()) put("reason", rejectReasonText.trim())
                                    }
                                    showRejectReasonInput = false
                                    onSubmitAction(card.cardId, card.cardType, "reject", payload)
                                },
                                enabled = !isSubmitting,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = nexaColors.crimson,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Text("Confirm Reject", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (!showRejectReasonInput) {
                    if (isSubmitting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Applying action...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val selectedCount = selectionMap.values.count { it }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            card.actions.forEach { act ->
                                val resolvedLabel = if (act.kind == "MULTI_SELECT_SUBMIT") {
                                    "${act.label} ($selectedCount)"
                                } else {
                                    act.label
                                }
                                val isActionEnabled = !(act.kind == "MULTI_SELECT_SUBMIT" && selectedCount == 0)
                                CardActionButton(
                                    action = act,
                                    label = resolvedLabel,
                                    enabled = isActionEnabled,
                                    onClick = {
                                        performTapHaptic()
                                        when (act.kind) {
                                            "PICK_CATEGORY" -> onOpenCategoryPicker(card)
                                            "PICK_ACCOUNT" -> onOpenAccountPicker(card)
                                            "PICK_METHOD" -> onOpenMethodPicker(card)
                                            "EDIT_FINANCE" -> onOpenFinanceEdit(card)
                                            "EDIT_VAULT" -> onOpenVaultEdit(card)
                                            "SEND_PROMPT" -> if (act.prompt.isNotBlank()) onQuickPrompt(act.prompt)
                                            "REJECT_WITH_REASON" -> showRejectReasonInput = true
                                            "MULTI_SELECT_SUBMIT" -> {
                                                val chosen = selectionMap.filterValues { it }.keys.toList()
                                                val payload = try {
                                                    JSONObject(card.metaJson)
                                                } catch (_: Exception) {
                                                    JSONObject()
                                                }.apply {
                                                    put("selected_ids", JSONArray(chosen))
                                                }
                                                onSubmitAction(card.cardId, card.cardType, act.id, payload)
                                            }
                                            else -> {
                                                val metaPayload = try {
                                                    JSONObject(card.metaJson)
                                                } catch (_: Exception) {
                                                    JSONObject()
                                                }
                                                onSubmitAction(card.cardId, card.cardType, act.id, metaPayload)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // Resolved / Expired locked footer
                val badgeText = card.resolvedBadge.ifEmpty {
                    when {
                        card.isExpired -> "Expired"
                        isLocallyExpired && card.expiresLabel.contains("Auto", ignoreCase = true) -> "Auto-saved"
                        isLocallyExpired -> "Expired"
                        else -> "Resolved"
                    }
                }
                val isNegative = badgeText.contains("Cancel", ignoreCase = true) ||
                    badgeText.contains("Reject", ignoreCase = true) ||
                    badgeText.contains("Fail", ignoreCase = true)
                val isMuted = card.isExpired || isLocallyExpired || badgeText.contains("Auto", ignoreCase = true)
                val statusColor = when {
                    isNegative -> nexaColors.crimson
                    isMuted -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> nexaColors.emerald
                }
                val statusIcon = when {
                    isNegative -> Icons.Default.Cancel
                    isMuted -> Icons.Default.Schedule
                    else -> Icons.Default.CheckCircle
                }

                HorizontalDivider(
                    thickness = 0.75.dp,
                    color = nexaColors.cardBorder.copy(alpha = 0.45f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                        val cleanDetail = stripCardEmojis(card.resolvedDetail)
                        if (cleanDetail.isNotEmpty()) {
                            Text(
                                text = cleanDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardActionButton(
    action: CardActionItem,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    when (action.style) {
        "PRIMARY" -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = nexaColors.primaryButtonFill,
                    contentColor = nexaColors.onPrimaryButtonFill
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        "DESTRUCTIVE" -> {
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, nexaColors.crimson.copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = nexaColors.crimson
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        else -> {
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, nexaColors.cardBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Compact Quick Action Chips strip rendered directly beneath N.E.X.A conversational text (Phase 2).
 * Used for CALENDAR_CONFLICT, FINANCE_DELETE, and TASK_CONFIRM (Sync / Duration / List).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NexaQuickActionChips(
    card: InteractiveCardData,
    isSubmitting: Boolean,
    errorMessage: String?,
    hapticEnabled: Boolean,
    onSubmitAction: (cardId: String, cardType: String, actionId: String, payload: JSONObject?) -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val haptic = LocalHapticFeedback.current

    var remainingSec by remember(card.cardId, card.expiresAtMs, card.status) {
        val initial = if (card.isPending && card.expiresAtMs > 0L) {
            ((card.expiresAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        mutableLongStateOf(initial)
    }
    var isLocallyExpired by remember(card.cardId, card.expiresAtMs, card.status) {
        mutableStateOf(
            card.isPending && card.expiresAtMs > 0L && System.currentTimeMillis() > card.expiresAtMs + 3_000L
        )
    }
    LaunchedEffect(card.cardId, card.expiresAtMs, card.status) {
        if (!card.isPending || card.expiresAtMs <= 0L) return@LaunchedEffect
        while (true) {
            val sec = ((card.expiresAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            remainingSec = sec
            if (sec <= 0L) {
                delay(3_000L)
                if (card.isPending) {
                    isLocallyExpired = true
                }
                break
            }
            delay(1_000L)
        }
    }
    val effectiveIsPending = card.isPending && !isLocallyExpired

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalDivider(
            thickness = 0.75.dp,
            color = nexaColors.cardBorder.copy(alpha = 0.55f)
        )

        if (effectiveIsPending) {
            if (card.expiresAtMs > 0L) {
                val mins = remainingSec / 60L
                val secs = remainingSec % 60L
                val prefix = card.expiresLabel.ifEmpty { "Expires in" }
                val timerText = if (remainingSec > 0L) {
                    "$prefix ${String.format(Locale.US, "%02d:%02d", mins, secs)}"
                } else {
                    "Finalizing..."
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = nexaColors.amber,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = timerText,
                        style = NexaMonoTypography.codeLine.copy(fontSize = 10.5.sp),
                        color = nexaColors.amber
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = nexaColors.crimson
                )
            }

            if (isSubmitting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Applying...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    card.actions.forEach { act ->
                        CardActionButton(
                            action = act,
                            label = act.label,
                            enabled = true,
                            onClick = {
                                if (hapticEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                val metaPayload = try {
                                    JSONObject(card.metaJson)
                                } catch (_: Exception) {
                                    JSONObject()
                                }
                                onSubmitAction(card.cardId, card.cardType, act.id, metaPayload)
                            }
                        )
                    }
                }
            }
        } else {
            val badgeText = card.resolvedBadge.ifEmpty {
                when {
                    card.isExpired -> "Expired"
                    isLocallyExpired && card.expiresLabel.contains("Auto", ignoreCase = true) -> "Auto-saved"
                    isLocallyExpired -> "Expired"
                    else -> "Resolved"
                }
            }
            val isNegative = badgeText.contains("Cancel", ignoreCase = true) ||
                badgeText.contains("Fail", ignoreCase = true) ||
                badgeText.contains("Skip", ignoreCase = true)
            val isMuted = card.isExpired || isLocallyExpired || badgeText.contains("Kept", ignoreCase = true) || badgeText.contains("Auto", ignoreCase = true)
            val statusColor = when {
                isNegative -> nexaColors.crimson
                isMuted -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> nexaColors.emerald
            }
            val statusIcon = when {
                isNegative -> Icons.Default.Cancel
                isMuted -> Icons.Default.Schedule
                else -> Icons.Default.CheckCircle
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp)
                )
                val cleanDetail = stripCardEmojis(card.resolvedDetail)
                Text(
                    text = if (cleanDetail.isNotEmpty()) {
                        "$badgeText \u00B7 $cleanDetail"
                    } else {
                        badgeText
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Native 1-tap Category Picker Modal Bottom Sheet for FINANCE_TX cards.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FinanceCategoryPickerSheet(
    card: InteractiveCardData,
    onSelectCategory: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val currentCategory = card.metaString("category")
    val categories = remember(card.categories) {
        if (card.categories.isNotEmpty()) {
            card.categories
        } else {
            listOf(
                "Makan Berat / Makan Luar",
                "Jajan / Ngopi / Kafe",
                "Transportasi Umum",
                "Belanja Harian / Groceries",
                "Tagihan & Utilitas",
                "Langganan",
                "Hiburan",
                "Kesehatan",
                "Lainnya"
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Select Category",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                categories.forEach { cat ->
                    val isSelected = cat.equals(currentCategory, ignoreCase = true)
                    Surface(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            nexaColors.elevatedSurface
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else nexaColors.cardBorder
                        ),
                        modifier = Modifier.clickable {
                            onSelectCategory(cat)
                        }
                    ) {
                        Text(
                            text = cat,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Native 1-tap Account Picker Modal Bottom Sheet for FINANCE_TX cards.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FinanceAccountPickerSheet(
    card: InteractiveCardData,
    onSelectAccount: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val currentAccount = card.metaString("account")
    val accounts = remember(card.accounts) {
        if (card.accounts.isNotEmpty()) {
            card.accounts
        } else {
            listOf("Bank Mandiri", "Bank Jago", "Shopee Pay", "Dana", "Gopay", "Tunai")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Select Account",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                accounts.forEach { acc ->
                    val isSelected = acc.equals(currentAccount, ignoreCase = true)
                    Surface(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            nexaColors.elevatedSurface
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else nexaColors.cardBorder
                        ),
                        modifier = Modifier.clickable { onSelectAccount(acc) }
                    ) {
                        Text(
                            text = acc,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Native 1-tap Payment Method Picker Modal Bottom Sheet for FINANCE_TX cards.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FinancePaymentMethodPickerSheet(
    card: InteractiveCardData,
    onSelectMethod: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val currentMethod = card.metaString("payment_method")
    val methods = remember(card.paymentMethods) {
        if (card.paymentMethods.isNotEmpty()) {
            card.paymentMethods
        } else {
            listOf("QRIS", "Direct Debit", "Transfer bank", "Kartu Kredit", "Tunai")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Select Payment Method",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                methods.forEach { method ->
                    val isSelected = method.equals(currentMethod, ignoreCase = true)
                    Surface(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            nexaColors.elevatedSurface
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else nexaColors.cardBorder
                        ),
                        modifier = Modifier.clickable { onSelectMethod(method) }
                    ) {
                        Text(
                            text = method,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Native Quick Edit Dialog for FINANCE_TX note, nominal, and natural Indonesian time expression.
 */
@Composable
fun FinanceQuickEditDialog(
    card: InteractiveCardData,
    onSave: (note: String, nominal: String, dateText: String) -> Unit,
    onDismiss: () -> Unit
) {
    var noteInput by remember(card.cardId, card.metaJson) { mutableStateOf(card.metaString("note")) }
    var nominalInput by remember(card.cardId, card.metaJson) {
        val n = card.metaLong("nominal", 0L)
        mutableStateOf(if (n > 0L) n.toString() else "")
    }
    var dateTextInput by remember(card.cardId, card.metaJson) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Transaction",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Note / Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nominalInput,
                    onValueChange = { raw -> nominalInput = raw.filter { it.isDigit() } },
                    label = { Text("Amount (IDR)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dateTextInput,
                    onValueChange = { dateTextInput = it },
                    label = { Text("Time (optional: tadi malam, jam 8 pagi)") },
                    placeholder = { Text(card.metaString("time_display").ifEmpty { "Leave blank to keep" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(noteInput.trim(), nominalInput.trim(), dateTextInput.trim()) },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Phase 3: Rich Executive Summary Card rendered atop Morning/Evening Briefings and Budget Recaps.
 * Displays hero metric, 2-column stat tiles, dynamic color-coded progress bars, and quick prompt chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NexaSummaryCard(
    card: InteractiveCardData,
    hapticEnabled: Boolean,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    fun resolveToneColor(tone: String): Color = when (tone.uppercase()) {
        "EMERALD" -> nexaColors.emerald
        "AMBER" -> nexaColors.amber
        "CRIMSON" -> nexaColors.crimson
        "PRIMARY" -> primaryColor
        else -> onSurfaceColor
    }

    val accentColor = resolveToneColor(card.accent)
    val cardBg = if (nexaColors.isDark) {
        Color(0xFF0B111A)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    val progressMetrics = card.metrics.filter { it.progress != null }
    val statMetrics = card.metrics.filter { it.progress == null }

    Surface(
        color = cardBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (card.subtitle.isNotEmpty()) {
                        Text(
                            text = card.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (card.badge.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = accentColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = card.badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (card.heroValue.isNotEmpty()) {
                val heroColor = when (card.heroTone) {
                    "INCOME" -> nexaColors.emerald
                    "EXPENSE" -> nexaColors.crimson
                    "TRANSFER" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    text = card.heroValue,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = heroColor
                )
            }

            // 2-column stat tiles (Briefing agenda/tasks/bills/goals)
            if (statMetrics.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    statMetrics.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { metric ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(0.75.dp, nexaColors.cardBorder.copy(alpha = 0.55f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = metric.label,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = metric.value,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = resolveToneColor(metric.tone),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (rowItems.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Horizontal color-coded progress rows (Budget Recap)
            if (progressMetrics.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    progressMetrics.forEach { metric ->
                        val barColor = resolveToneColor(metric.tone)
                        val pct = (metric.progress ?: 0).coerceIn(0, 100)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = metric.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = metric.value,
                                    style = NexaMonoTypography.codeLine.copy(fontSize = 10.5.sp),
                                    color = barColor,
                                    maxLines = 1
                                )
                            }
                            LinearProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = barColor,
                                trackColor = nexaColors.elevatedSurface
                            )
                        }
                    }
                }
            }

            // Quick Follow-Up Prompt Chips
            val promptActions = card.actions.filter { it.kind == "SEND_PROMPT" && it.prompt.isNotBlank() }
            if (promptActions.isNotEmpty()) {
                HorizontalDivider(
                    thickness = 0.75.dp,
                    color = nexaColors.cardBorder.copy(alpha = 0.5f)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    promptActions.forEach { act ->
                        CardActionButton(
                            action = act,
                            label = act.label,
                            enabled = true,
                            onClick = {
                                if (hapticEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                onQuickPrompt(act.prompt)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Phase 3: Native Vault Metadata Editor Dialog.
 * Allows Tuan to tap and edit any Vault field directly (plus add new fields) without typing `EDIT key: value;`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultMetadataEditDialog(
    card: InteractiveCardData,
    onSave: (category: String, edits: Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val presetCategories = remember { listOf("IDENTITAS", "DOKUMEN", "ARSIP", "STRUK", "LAINNYA") }
    var selectedCategory by remember(card.cardId, card.metaJson) {
        mutableStateOf(card.metaString("category", "ARSIP").ifBlank { "ARSIP" }.uppercase())
    }
    val fieldValues = remember(card.cardId, card.fields) {
        mutableStateMapOf<String, String>().apply {
            card.fields.forEach { put(it.key, it.value) }
        }
    }
    var newFieldKey by remember(card.cardId) { mutableStateOf("") }
    var newFieldValue by remember(card.cardId) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Vault Metadata",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    presetCategories.forEach { cat ->
                        val isChosen = selectedCategory.equals(cat, ignoreCase = true)
                        Surface(
                            color = if (isChosen) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                nexaColors.elevatedSurface
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isChosen) MaterialTheme.colorScheme.primary else nexaColors.cardBorder
                            ),
                            modifier = Modifier.clickable { selectedCategory = cat }
                        ) {
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 0.75.dp, color = nexaColors.cardBorder.copy(alpha = 0.5f))

                card.fields.forEach { field ->
                    OutlinedTextField(
                        value = fieldValues[field.key] ?: "",
                        onValueChange = { fieldValues[field.key] = it },
                        label = { Text(field.label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(thickness = 0.75.dp, color = nexaColors.cardBorder.copy(alpha = 0.5f))
                Text(
                    text = "Add Field (optional)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = newFieldKey,
                    onValueChange = { newFieldKey = it },
                    label = { Text("Field Name (e.g. masa_berlaku)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newFieldValue,
                    onValueChange = { newFieldValue = it },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val merged = LinkedHashMap<String, String>()
                    fieldValues.forEach { (k, v) -> merged[k] = v.trim() }
                    val extraKey = newFieldKey.trim()
                    val extraVal = newFieldValue.trim()
                    if (extraKey.isNotEmpty() && extraVal.isNotEmpty()) {
                        merged[extraKey] = extraVal
                    }
                    onSave(selectedCategory, merged)
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Ultra-clean Receipt-First layout for FINANCE_TX cards:
 * - Zero nested borders (renders directly as the chat bubble surface when framed = false)
 * - Hero nominal + subject/time at top for 0.5-second scanning
 * - Direct-tap rows for Account, Method, and Category (no 6-button clutter)
 * - Single 2-button footer: Cancel | Select Account (or Confirm)
 */
@Composable
private fun FinanceTxReceiptCard(
    card: InteractiveCardData,
    effectiveIsPending: Boolean,
    isLocallyExpired: Boolean,
    remainingSec: Long,
    isSubmitting: Boolean,
    errorMessage: String?,
    framed: Boolean,
    onTapHaptic: () -> Unit,
    onSubmitAction: (cardId: String, cardType: String, actionId: String, payload: JSONObject?) -> Unit,
    onOpenCategoryPicker: (InteractiveCardData) -> Unit,
    onOpenAccountPicker: (InteractiveCardData) -> Unit,
    onOpenMethodPicker: (InteractiveCardData) -> Unit,
    onOpenFinanceEdit: (InteractiveCardData) -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val isTransfer = card.heroTone == "TRANSFER"
    val accentColor = when (card.accent) {
        "EMERALD" -> nexaColors.emerald
        "AMBER" -> nexaColors.amber
        "CRIMSON" -> nexaColors.crimson
        else -> MaterialTheme.colorScheme.primary
    }
    val heroColor = when (card.heroTone) {
        "INCOME" -> nexaColors.emerald
        "TRANSFER" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val fieldByKey = remember(card.fields) { card.fields.associateBy { it.key } }
    val rawAccount = card.metaString("account").ifBlank {
        fieldByKey["account"]?.value?.takeIf { !it.equals("Not selected", true) && !it.equals("Unassigned", true) } ?: ""
    }
    val hasAccount = isTransfer || rawAccount.isNotBlank()
    val rawMethod = card.metaString("payment_method").ifBlank {
        fieldByKey["payment_method"]?.value?.takeIf { !it.equals("Not selected", true) && it != "-" } ?: ""
    }
    val hasMethod = rawMethod.isNotBlank()
    val categoryVal = fieldByKey["category"]?.value?.ifBlank { card.metaString("category", "Lainnya") }
        ?: card.metaString("category", "Lainnya")
    val noteVal = card.metaString("note").ifBlank {
        fieldByKey["note"]?.value?.takeIf { !it.equals("Pending note", true) && it != "-" } ?: ""
    }
    val merchantVal = fieldByKey["merchant"]?.value?.takeIf {
        it.isNotBlank() && !it.equals("Unknown", true) && it != "-"
    } ?: ""
    val timeDisplay = (fieldByKey["time"]?.value ?: card.metaString("time_display"))
        .replace(" WIB", "")
        .replace(" \u00B7 ", ", ")
        .trim()
    val adminFeeVal = fieldByKey["admin_fee"]?.value ?: ""

    val subjectLine = remember(merchantVal, noteVal) {
        when {
            merchantVal.isNotEmpty() && noteVal.isNotEmpty() &&
                !noteVal.contains(merchantVal, ignoreCase = true) -> "$merchantVal \u00B7 $noteVal"
            noteVal.isNotEmpty() -> noteVal
            merchantVal.isNotEmpty() -> merchantVal
            else -> ""
        }
    }

    val cardBg = if (nexaColors.isDark) Color(0xFF0B111A) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val outerColor = if (framed) cardBg else Color.Transparent
    val outerBorder = if (framed) {
        BorderStroke(1.dp, if (effectiveIsPending) accentColor.copy(alpha = 0.35f) else nexaColors.cardBorder.copy(alpha = 0.6f))
    } else null

    Surface(
        color = outerColor,
        shape = RoundedCornerShape(12.dp),
        border = outerBorder,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (framed) 12.dp else 0.dp, vertical = if (framed) 10.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Hero Row: Amount + Type Pill + Subtle Edit Pencil
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = card.heroValue.ifEmpty { card.title },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = heroColor,
                    maxLines = 1
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (card.badge.isNotEmpty()) {
                        Surface(
                            color = accentColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = card.badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    if (effectiveIsPending && !isSubmitting) {
                        IconButton(
                            onClick = {
                                onTapHaptic()
                                onOpenFinanceEdit(card)
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit transaction",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // 2. Calm Subject & Time Line (tap to edit note/time)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = effectiveIsPending && !isSubmitting) {
                        onTapHaptic()
                        onOpenFinanceEdit(card)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displaySubject = subjectLine.ifEmpty { "Tap to add note" }
                val subjectColor = if (subjectLine.isNotEmpty()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    nexaColors.amber
                }
                Text(
                    text = displaySubject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = subjectColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (timeDisplay.isNotEmpty()) {
                    Text(
                        text = " \u00B7 $timeDisplay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            // 3. Single-Line Micro-Status (Auto-save paused OR live countdown)
            if (effectiveIsPending) {
                if (!hasAccount) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(nexaColors.amber)
                        )
                        Text(
                            text = "Select account \u00B7 auto-save paused",
                            style = MaterialTheme.typography.labelSmall,
                            color = nexaColors.amber,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (card.expiresAtMs > 0L) {
                    val mins = remainingSec / 60L
                    val secs = remainingSec % 60L
                    val timerLabel = if (remainingSec > 0L) {
                        "Auto-saves in ${String.format(Locale.US, "%02d:%02d", mins, secs)}"
                    } else {
                        "Finalizing..."
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = nexaColors.amber,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = timerLabel,
                            style = NexaMonoTypography.codeLine.copy(fontSize = 11.sp),
                            color = nexaColors.amber
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.6.dp, color = nexaColors.cardBorder.copy(alpha = 0.55f))

            // 4. Direct-Tap Clean Rows (Account / Method / Category)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isTransfer && merchantVal.isNotEmpty()) {
                    FinanceReceiptRow(
                        label = "Route",
                        value = merchantVal,
                        isPlaceholder = false,
                        isRequiredMissing = false,
                        interactive = false,
                        onClick = {}
                    )
                } else {
                    FinanceReceiptRow(
                        label = "Account",
                        value = if (hasAccount) rawAccount else (if (effectiveIsPending) "Select" else "-"),
                        isPlaceholder = !hasAccount,
                        isRequiredMissing = !hasAccount && effectiveIsPending,
                        interactive = effectiveIsPending && !isSubmitting,
                        onClick = {
                            onTapHaptic()
                            onOpenAccountPicker(card)
                        }
                    )
                    FinanceReceiptRow(
                        label = "Method",
                        value = if (hasMethod) rawMethod else (if (effectiveIsPending) "Optional" else "-"),
                        isPlaceholder = !hasMethod,
                        isRequiredMissing = false,
                        interactive = effectiveIsPending && !isSubmitting,
                        onClick = {
                            onTapHaptic()
                            onOpenMethodPicker(card)
                        }
                    )
                }
                FinanceReceiptRow(
                    label = "Category",
                    value = categoryVal,
                    isPlaceholder = false,
                    isRequiredMissing = false,
                    interactive = effectiveIsPending && !isSubmitting,
                    onClick = {
                        onTapHaptic()
                        onOpenCategoryPicker(card)
                    }
                )
                if (adminFeeVal.isNotEmpty()) {
                    FinanceReceiptRow(
                        label = "Admin Fee",
                        value = adminFeeVal,
                        isPlaceholder = false,
                        isRequiredMissing = false,
                        interactive = false,
                        onClick = {}
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = nexaColors.crimson
                )
            }

            HorizontalDivider(thickness = 0.6.dp, color = nexaColors.cardBorder.copy(alpha = 0.55f))

            // 5. Clean 2-Button Footer OR Single-Line Resolved Status
            if (effectiveIsPending) {
                if (isSubmitting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Saving...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                onTapHaptic()
                                val metaPayload = try { JSONObject(card.metaJson) } catch (_: Exception) { JSONObject() }
                                onSubmitAction(card.cardId, card.cardType, "cancel", metaPayload)
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, nexaColors.crimson.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = nexaColors.crimson),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(0.38f)
                                .heightIn(min = 38.dp)
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                onTapHaptic()
                                if (!hasAccount) {
                                    onOpenAccountPicker(card)
                                } else {
                                    val metaPayload = try { JSONObject(card.metaJson) } catch (_: Exception) { JSONObject() }
                                    onSubmitAction(card.cardId, card.cardType, "confirm", metaPayload)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = nexaColors.primaryButtonFill,
                                contentColor = nexaColors.onPrimaryButtonFill
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(0.62f)
                                .heightIn(min = 38.dp)
                        ) {
                            Text(
                                text = if (!hasAccount) "Select Account" else "Confirm",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else {
                val badgeText = card.resolvedBadge.ifEmpty {
                    when {
                        card.isExpired -> "Expired"
                        isLocallyExpired && card.expiresLabel.contains("Auto", ignoreCase = true) -> "Auto-saved"
                        isLocallyExpired -> "Expired"
                        else -> "Recorded"
                    }
                }
                val isNegative = badgeText.contains("Cancel", ignoreCase = true) ||
                    badgeText.contains("Fail", ignoreCase = true)
                val isMuted = card.isExpired || isLocallyExpired || badgeText.contains("Auto", ignoreCase = true)
                val statusColor = when {
                    isNegative -> nexaColors.crimson
                    isMuted -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> nexaColors.emerald
                }
                val statusIcon = when {
                    isNegative -> Icons.Default.Cancel
                    isMuted -> Icons.Default.Schedule
                    else -> Icons.Default.CheckCircle
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FinanceReceiptRow(
    label: String,
    value: String,
    isPlaceholder: Boolean,
    isRequiredMissing: Boolean,
    interactive: Boolean,
    onClick: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = interactive, onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isPlaceholder && interactive) {
            val pillColor = if (isRequiredMissing) nexaColors.amber else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                color = pillColor.copy(alpha = if (isRequiredMissing) 0.14f else 0.10f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isRequiredMissing) FontWeight.SemiBold else FontWeight.Medium,
                        color = pillColor
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = pillColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isPlaceholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (interactive) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

private fun stripCardEmojis(raw: String): String {
    if (raw.isEmpty()) return ""
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        val charCount = Character.charCount(cp)
        val isEmoji = (cp in 0x1F000..0x1FAFF) ||
            (cp in 0x2600..0x27BF) ||
            (cp in 0x2300..0x23FF) ||
            cp == 0xFE0F ||
            cp == 0x200D
        if (!isEmoji) {
            sb.appendCodePoint(cp)
        }
        i += charCount
    }
    return sb.toString().replace(Regex("\\s+"), " ").trim()
}
