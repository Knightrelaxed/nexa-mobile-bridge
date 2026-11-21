package com.nexa.mobilebridge.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobilebridge.data.terminal.ModifierState
import com.nexa.mobilebridge.data.terminal.StickyModifierMode
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class SecondaryKeyDef(
    val label: String,
    val bytes: ByteArray
)

/**
 * 2-Row Ergonomic Terminal Accessory Bar matching Stitch Blueprint v2 (04_terminal_pty_v2.png)
 * and PRD Amandemen §12.3 (Galaxy A33 411 dp):
 * - Row 1 (9 fixed keys, >= 44x48 dp): [ESC] [TAB] [CTRL] [ALT] [<-] [v] [^] [->] [...]
 *   - Arrow keys support key-repeat (400 ms hold delay, 40 ms repeat interval, FR-TERM-08)
 *   - CTRL / ALT visually distinguish IDLE, ONCE (cyan outline + dot), and LOCKED (cyan fill + lock icon)
 * - Row 2 (toggled via [...], scrollable LazyRow, uniform monochrome styling without rainbow colors):
 *   [^C] [^D] [^Z] [^L] [-] [/] [|] [~] [$] [*] [Home] [End] [PgUp] [PgDn]
 */
@Composable
fun TerminalAccessoryBar(
    modifiers: ModifierState,
    isRow2Expanded: Boolean,
    onToggleRow2: () -> Unit,
    onSendBytes: (ByteArray) -> Unit,
    onHapticTick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modState by modifiers.state.collectAsStateWithLifecycle()
    val nexaColors = LocalNexaColors.current

    val row2Keys = remember {
        listOf(
            SecondaryKeyDef("^C", byteArrayOf(0x03)),
            SecondaryKeyDef("^D", byteArrayOf(0x04)),
            SecondaryKeyDef("^Z", byteArrayOf(0x1A)),
            SecondaryKeyDef("^L", byteArrayOf(0x0C)),
            SecondaryKeyDef("-", "-".toByteArray()),
            SecondaryKeyDef("/", "/".toByteArray()),
            SecondaryKeyDef("|", "|".toByteArray()),
            SecondaryKeyDef("~", "~".toByteArray()),
            SecondaryKeyDef("$", "$".toByteArray()),
            SecondaryKeyDef("*", "*".toByteArray()),
            SecondaryKeyDef("Home", "\u001B[H".toByteArray()),
            SecondaryKeyDef("End", "\u001B[F".toByteArray()),
            SecondaryKeyDef("PgUp", "\u001B[5~".toByteArray()),
            SecondaryKeyDef("PgDn", "\u001B[6~".toByteArray())
        )
    }

    Surface(
        color = if (nexaColors.isDark) Color(0xFF0C1118) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, nexaColors.cardBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Row 1: 9 fixed buttons sized cleanly across 411 dp (~42.6-46 dp wide x 48 dp tall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccessoryKeyButton(
                    label = "ESC",
                    accessibilityLabel = "Tombol Escape",
                    onClick = {
                        onHapticTick()
                        onSendBytes(byteArrayOf(0x1B))
                    },
                    modifier = Modifier.weight(1f)
                )

                AccessoryKeyButton(
                    label = "TAB",
                    accessibilityLabel = "Tombol Tab",
                    onClick = {
                        onHapticTick()
                        onSendBytes(byteArrayOf(0x09))
                    },
                    modifier = Modifier.weight(1f)
                )

                StickyModifierKeyButton(
                    label = "CTRL",
                    mode = modState.ctrl,
                    onClick = {
                        onHapticTick()
                        modifiers.toggleCtrl()
                    },
                    modifier = Modifier.weight(1.08f)
                )

                StickyModifierKeyButton(
                    label = "ALT",
                    mode = modState.alt,
                    onClick = {
                        onHapticTick()
                        modifiers.toggleAlt()
                    },
                    modifier = Modifier.weight(1f)
                )

                // Right-thumb Arrow Cluster with Key-Repeat (400 ms delay, 40 ms interval, FR-TERM-08)
                RepeatableArrowButton(
                    symbol = "\u2190",
                    accessibilityLabel = "Panah Kiri",
                    onFire = { onSendBytes("\u001B[D".toByteArray()) },
                    onHapticTick = onHapticTick,
                    modifier = Modifier.weight(1f)
                )
                RepeatableArrowButton(
                    symbol = "\u2193",
                    accessibilityLabel = "Panah Bawah",
                    onFire = { onSendBytes("\u001B[B".toByteArray()) },
                    onHapticTick = onHapticTick,
                    modifier = Modifier.weight(1f)
                )
                RepeatableArrowButton(
                    symbol = "\u2191",
                    accessibilityLabel = "Panah Atas",
                    onFire = { onSendBytes("\u001B[A".toByteArray()) },
                    onHapticTick = onHapticTick,
                    modifier = Modifier.weight(1f)
                )
                RepeatableArrowButton(
                    symbol = "\u2192",
                    accessibilityLabel = "Panah Kanan",
                    onFire = { onSendBytes("\u001B[C".toByteArray()) },
                    onHapticTick = onHapticTick,
                    modifier = Modifier.weight(1f)
                )

                AccessoryKeyButton(
                    label = "\u22EF",
                    accessibilityLabel = if (isRow2Expanded) {
                        "Sembunyikan tombol tambahan"
                    } else {
                        "Tampilkan tombol tambahan"
                    },
                    isHighlighted = isRow2Expanded,
                    onClick = {
                        onHapticTick()
                        onToggleRow2()
                    },
                    modifier = Modifier.weight(0.92f)
                )
            }

            // Row 2: Expandable horizontal LazyRow (hidden by default per PRD §12.3)
            if (isRow2Expanded) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(row2Keys, key = { it.label }) { item ->
                        AccessoryKeyButton(
                            label = item.label,
                            onClick = {
                                onHapticTick()
                                onSendBytes(item.bytes)
                            },
                            modifier = Modifier.widthIn(min = 48.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessoryKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    accessibilityLabel: String = label
) {
    val nexaColors = LocalNexaColors.current
    val bg = when {
        isHighlighted && nexaColors.isDark -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        isHighlighted -> MaterialTheme.colorScheme.primaryContainer
        nexaColors.isDark -> Color(0xFF151D29)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val border = if (isHighlighted) MaterialTheme.colorScheme.primary else nexaColors.cardBorder
    val fg = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .height(48.dp)
            .semantics {
                contentDescription = accessibilityLabel
            }
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            Text(
                text = label,
                style = NexaMonoTypography.badge.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                fontWeight = FontWeight.Bold,
                color = fg,
                maxLines = 1
            )
        }
    }
}

/**
 * Sticky CTRL / ALT button with non-color-only visual state cues (FR-TERM-07, PRD §12.3):
 * - IDLE: standard dark key
 * - ONCE: cyan outline + cyan indicator dot below text
 * - LOCKED: accent fill + contrasting text + small lock icon
 */
@Composable
private fun StickyModifierKeyButton(
    label: String,
    mode: StickyModifierMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val bg = when (mode) {
        StickyModifierMode.LOCKED -> nexaColors.primaryButtonFill
        StickyModifierMode.ONCE -> if (nexaColors.isDark) Color(0xFF10202A) else MaterialTheme.colorScheme.primaryContainer
        StickyModifierMode.IDLE -> if (nexaColors.isDark) Color(0xFF151D29) else MaterialTheme.colorScheme.surfaceVariant
    }
    val border = when (mode) {
        StickyModifierMode.LOCKED -> nexaColors.primaryButtonFill
        StickyModifierMode.ONCE -> MaterialTheme.colorScheme.primary
        StickyModifierMode.IDLE -> nexaColors.cardBorder
    }
    val fg = when (mode) {
        StickyModifierMode.LOCKED -> nexaColors.onPrimaryButtonFill
        StickyModifierMode.ONCE -> MaterialTheme.colorScheme.onSurface
        StickyModifierMode.IDLE -> MaterialTheme.colorScheme.onSurface
    }
    val modeDesc = when (mode) {
        StickyModifierMode.IDLE -> "$label off"
        StickyModifierMode.ONCE -> "$label once"
        StickyModifierMode.LOCKED -> "$label locked"
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (mode == StickyModifierMode.ONCE) 1.5.dp else 1.dp, border),
        modifier = modifier
            .height(48.dp)
            .semantics {
                contentDescription = modeDesc
            }
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            when (mode) {
                StickyModifierMode.LOCKED -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = label,
                            style = NexaMonoTypography.badge.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = fg,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(1.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(9.dp)
                        )
                    }
                }

                StickyModifierMode.ONCE -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = label,
                            style = NexaMonoTypography.badge.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                            fontWeight = FontWeight.Bold,
                            color = fg,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                }

                StickyModifierMode.IDLE -> {
                    Text(
                        text = label,
                        style = NexaMonoTypography.badge.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                        fontWeight = FontWeight.Bold,
                        color = fg,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Arrow button with press-and-hold key-repeat: fires immediately, waits 400 ms,
 * then repeats every 40 ms until released (FR-TERM-08).
 */
@Composable
private fun RepeatableArrowButton(
    symbol: String,
    accessibilityLabel: String,
    onFire: () -> Unit,
    onHapticTick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val scope = rememberCoroutineScope()
    val latestOnFire by rememberUpdatedState(onFire)
    val latestOnHaptic by rememberUpdatedState(onHapticTick)

    Surface(
        color = if (nexaColors.isDark) Color(0xFF151D29) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, nexaColors.cardBorder),
        modifier = modifier
            .height(48.dp)
            .semantics {
                contentDescription = accessibilityLabel
                role = Role.Button
                onClick(label = accessibilityLabel) {
                    latestOnHaptic()
                    latestOnFire()
                    true
                }
            }
            .pointerInput(symbol) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    latestOnHaptic()
                    latestOnFire()
                    val repeatJob = scope.launch {
                        delay(400L)
                        while (true) {
                            latestOnFire()
                            delay(40L)
                        }
                    }
                    waitForUpOrCancellation()
                    repeatJob.cancel()
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                style = NexaMonoTypography.metricMedium.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
