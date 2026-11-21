package com.nexa.mobilebridge.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.mobilebridge.theme.LocalNexaColors

internal fun formatFingerprintPreview(colonHex: String): String {
    val clean = colonHex.trim()
    if (clean.isEmpty()) return "Not available"
    val octets = clean.split(":").filter { it.isNotBlank() }
    return if (octets.size > 4) {
        octets.take(4).joinToString(":") + "..."
    } else {
        clean
    }
}

internal fun extractDisplayHost(wsUrl: String): String {
    val trimmed = wsUrl.trim()
    if (trimmed.isEmpty()) return "Not set"
    return trimmed
        .removePrefix("wss://")
        .removePrefix("ws://")
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .ifBlank { trimmed }
}

/**
 * Quiet sans-serif section title placed outside a grouped surface (8dp spacing grid).
 */
@Composable
internal fun SettingsGroupHeader(
    title: String,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                letterSpacing = 0.9.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
        if (trailingActionLabel != null && onTrailingAction != null) {
            Text(
                text = trailingActionLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onTrailingAction)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Single clean grouped surface with 16dp corner radius and zero nested box clutter.
 */
@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val nexaColors = LocalNexaColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = nexaColors.cardBorder.copy(alpha = if (nexaColors.isDark) 0.55f else 0.75f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * Hairline separator between rows inside a SettingsGroupCard.
 */
@Composable
internal fun SettingsRowDivider(insetStart: Boolean = true) {
    val nexaColors = LocalNexaColors.current
    HorizontalDivider(
        thickness = 0.75.dp,
        color = nexaColors.cardBorder.copy(alpha = if (nexaColors.isDark) 0.42f else 0.6f),
        modifier = Modifier.padding(start = if (insetStart) 60.dp else 16.dp, end = 16.dp)
    )
}

/**
 * Uniform 32dp neutral circular icon badge (neutral first; accent reserved for semantic state).
 */
@Composable
internal fun SettingsLeadingIcon(
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color? = null
) {
    val nexaColors = LocalNexaColors.current
    val bg = containerColor ?: nexaColors.elevatedSurface
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Standard >= 56dp grouped settings row with neutral leading icon, clean typography,
 * monospace option strictly for technical values, and optional trailing content/chevron.
 */
@Composable
internal fun SettingsListRow(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitle: String? = null,
    subtitleMonospace: Boolean = false,
    subtitleColor: Color? = null,
    trailingText: String? = null,
    trailingMonospace: Boolean = false,
    trailingColor: Color? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val clickMod = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(clickMod)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (leadingIcon != null) {
            SettingsLeadingIcon(icon = leadingIcon, tint = leadingTint)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = if (subtitleMonospace) {
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailing != null) {
            trailing()
        } else {
            if (!trailingText.isNullOrBlank()) {
                Text(
                    text = trailingText,
                    style = if (trailingMonospace) {
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    fontWeight = FontWeight.Medium,
                    color = trailingColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Clean segmented control supporting 2..4 options with >= 44dp touch height (or 40dp compact).
 */
@Composable
internal fun <T> SettingsSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    emphasizeWithAccentFill: Boolean = true,
    compact: Boolean = false
) {
    val nexaColors = LocalNexaColors.current
    val trackColor = if (nexaColors.isDark) Color(0xFF0A1017) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)

    Surface(
        color = trackColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, nexaColors.cardBorder.copy(alpha = 0.55f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val targetBg = when {
                    isSelected && emphasizeWithAccentFill -> nexaColors.primaryButtonFill
                    isSelected -> if (nexaColors.isDark) Color(0xFF1D2938) else MaterialTheme.colorScheme.surface
                    else -> Color.Transparent
                }
                val targetFg = when {
                    isSelected && emphasizeWithAccentFill -> nexaColors.onPrimaryButtonFill
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val bg by animateColorAsState(targetBg, tween(160), label = "segBg")
                val fg by animateColorAsState(targetFg, tween(160), label = "segFg")

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = if (compact) 40.dp else 44.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(bg)
                        .semantics {
                            this.selected = isSelected
                            contentDescription = label
                        }
                        .clickable(role = Role.RadioButton) { onSelect(value) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = if (compact) {
                            MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsNoticeBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    Surface(
        color = if (nexaColors.isDark) Color(0xFF13222E) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text("Dismiss", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun StatusIndicatorDot(
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}
