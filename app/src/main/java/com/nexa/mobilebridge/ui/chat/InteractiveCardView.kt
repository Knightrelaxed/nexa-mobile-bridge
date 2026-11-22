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
    LaunchedEffect(card.cardId, card.expiresAtMs, card.status) {
        if (!card.isPending || card.expiresAtMs <= 0L) return@LaunchedEffect
        while (true) {
            val sec = ((card.expiresAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            remainingSec = sec
            if (sec <= 0L) break
            delay(1_000L)
        }
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

    Surface(
        color = cardBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (card.isPending) accentColor.copy(alpha = 0.35f) else nexaColors.cardBorder.copy(alpha = 0.6f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
            if (card.heroValue.isNotEmpty() || (card.isPending && card.expiresAtMs > 0L)) {
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

                    if (card.isPending && card.expiresAtMs > 0L) {
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
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
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
                                .clickable(enabled = card.isPending && !isSubmitting) {
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
                                    enabled = card.isPending && !isSubmitting,
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
            if (card.isPending) {
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
                                            "EDIT_FINANCE" -> onOpenFinanceEdit(card)
                                            "REJECT_WITH_REASON" -> showRejectReasonInput = true
                                            "MULTI_SELECT_SUBMIT" -> {
                                                val chosen = selectionMap.filterValues { it }.keys.toList()
                                                val payload = JSONObject().apply {
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
                    if (card.isExpired) "Expired" else "Resolved"
                }
                val isNegative = badgeText.contains("Cancel", ignoreCase = true) ||
                    badgeText.contains("Reject", ignoreCase = true) ||
                    badgeText.contains("Fail", ignoreCase = true)
                val isMuted = card.isExpired || badgeText.contains("Auto", ignoreCase = true)
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
                        if (card.resolvedDetail.isNotEmpty()) {
                            Text(
                                text = card.resolvedDetail,
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
 * Native Quick Edit Dialog for FINANCE_TX note and nominal fields.
 */
@Composable
fun FinanceQuickEditDialog(
    card: InteractiveCardData,
    onSave: (note: String, nominal: String) -> Unit,
    onDismiss: () -> Unit
) {
    var noteInput by remember(card.cardId, card.metaJson) { mutableStateOf(card.metaString("note")) }
    var nominalInput by remember(card.cardId, card.metaJson) {
        val n = card.metaLong("nominal", 0L)
        mutableStateOf(if (n > 0L) n.toString() else "")
    }

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
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(noteInput.trim(), nominalInput.trim()) },
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
