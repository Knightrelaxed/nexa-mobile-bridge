package com.nexa.mobilebridge.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nexa.mobilebridge.data.local.chat.ChatMessageEntity
import com.nexa.mobilebridge.theme.LocalNexaColors

/**
 * Anchor payload for the single-tap Telegram-style message action menu.
 */
data class MessageMenuAnchor(
    val message: ChatMessageEntity,
    val displayContent: String,
    val isVoice: Boolean,
    val tapWindowOffset: IntOffset
) {
    val canCopy: Boolean
        get() = !isVoice && displayContent.isNotBlank()
}

/**
 * Compact contextual menu displayed near the tapped coordinate of a chat bubble.
 * Automatically clamps inside window bounds and flips upward near the bottom of the screen.
 */
@Composable
fun MessageActionMenuPopup(
    anchor: MessageMenuAnchor,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val density = LocalDensity.current
    val edgeMarginPx = with(density) { 12.dp.roundToPx() }
    val verticalGapPx = with(density) { 8.dp.roundToPx() }

    val positionProvider = remember(anchor.tapWindowOffset, edgeMarginPx, verticalGapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val tapX = anchor.tapWindowOffset.x
                val tapY = anchor.tapWindowOffset.y

                val rawX = tapX - (popupContentSize.width / 2)
                val maxX = (windowSize.width - popupContentSize.width - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                val clampedX = rawX.coerceIn(edgeMarginPx, maxX)

                val fitsBelow = tapY + verticalGapPx + popupContentSize.height <= windowSize.height - edgeMarginPx
                val rawY = if (fitsBelow) {
                    tapY + verticalGapPx
                } else {
                    tapY - verticalGapPx - popupContentSize.height
                }
                val maxY = (windowSize.height - popupContentSize.height - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                val clampedY = rawY.coerceIn(edgeMarginPx, maxY)

                return IntOffset(clampedX, clampedY)
            }
        }
    }

    val menuBg = if (nexaColors.isDark) Color(0xFF17202D) else MaterialTheme.colorScheme.surface

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            color = menuBg,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, nexaColors.cardBorder),
            shadowElevation = 10.dp,
            modifier = Modifier.width(188.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                MessageActionMenuRow(
                    icon = Icons.AutoMirrored.Filled.Reply,
                    label = "Reply",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onReply
                )

                if (anchor.canCopy) {
                    MessageActionMenuRow(
                        icon = Icons.Default.ContentCopy,
                        label = "Copy",
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = onCopy
                    )
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = nexaColors.cardBorder.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )

                MessageActionMenuRow(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    tint = nexaColors.crimson,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun MessageActionMenuRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

/**
 * Contextual Telegram-style selection header matching NexaTopBar exact height (62 dp + statusBarsPadding + 1 dp divider)
 * so toggling selection mode never shifts the chat list vertically.
 */
@Composable
fun ChatSelectionTopBar(
    selectedCount: Int,
    canReply: Boolean,
    canCopy: Boolean,
    onClear: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val barBg = if (nexaColors.isDark) Color(0xFF101925) else MaterialTheme.colorScheme.surface

    Surface(
        color = barBg,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(62.dp)
                    .padding(start = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel selection",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "$selectedCount selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canReply) {
                        IconButton(
                            onClick = onReply,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = "Reply to selected message",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    if (canCopy) {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy selected messages",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete selected messages",
                            tint = nexaColors.crimson
                        )
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            )
        }
    }
}

/**
 * Compact 22 dp Telegram-style circular checkbox displayed on the left edge in selection mode.
 */
@Composable
fun SelectionCheckCircle(
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.5.dp, borderColor, CircleShape)
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = nexaColors.onPrimaryButtonFill,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun DeleteMessagesConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val titleText = if (count <= 1) "Delete message?" else "Delete $count messages?"
    val bodyText = if (count <= 1) {
        "This removes the message from this device only. N.E.X.A's memory on the server is unaffected."
    } else {
        "This removes $count selected messages from this device only. N.E.X.A's memory on the server is unaffected."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = "Delete",
                    color = nexaColors.crimson,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}
