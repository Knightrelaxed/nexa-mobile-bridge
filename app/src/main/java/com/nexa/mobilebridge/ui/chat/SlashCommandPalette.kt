package com.nexa.mobilebridge.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.mobilebridge.theme.LocalNexaColors

data class SlashCommandSpec(
    val command: String,
    val title: String,
    val description: String,
    val requiresArgument: Boolean = false,
    val argHint: String = ""
)

val NEXA_SLASH_COMMANDS: List<SlashCommandSpec> = listOf(
    SlashCommandSpec(
        command = "/briefing",
        title = "Morning Briefing",
        description = "Executive summary card with weather, agenda, tasks & safe-to-spend"
    ),
    SlashCommandSpec(
        command = "/budget",
        title = "Budget Recap",
        description = "Live spending progress bars across monthly or weekly budget groups"
    ),
    SlashCommandSpec(
        command = "/vault",
        title = "Vault Metadata",
        description = "Review and edit latest Vault document draft fields natively"
    ),
    SlashCommandSpec(
        command = "/search",
        title = "Search History",
        description = "Search past conversations in long-term memory",
        requiresArgument = true,
        argHint = "<keyword>"
    ),
    SlashCommandSpec(
        command = "/playbook",
        title = "AI Playbooks",
        description = "Inspect active adaptive playbooks or undo an intent rule"
    ),
    SlashCommandSpec(
        command = "/capabilities",
        title = "Capabilities",
        description = "View official N.E.X.A system modules and version"
    ),
    SlashCommandSpec(
        command = "/trigger_weekly",
        title = "Weekly Identity Pass",
        description = "Run weekly cognitive identity inference right now"
    )
)

/**
 * Pure filter for `/` command palette:
 * - Active only when draft starts with `/` and has no whitespace/newline yet.
 * - Matches command name or title prefix/substring case-insensitively.
 */
fun filterSlashCommands(draftText: String): List<SlashCommandSpec> {
    if (!draftText.startsWith("/")) return emptyList()
    if (draftText.any { it == ' ' || it == '\n' || it == '\t' }) return emptyList()
    val query = draftText.trim().lowercase()
    if (query == "/") return NEXA_SLASH_COMMANDS
    return NEXA_SLASH_COMMANDS.filter { spec ->
        spec.command.lowercase().startsWith(query) ||
            spec.command.lowercase().contains(query.removePrefix("/")) ||
            spec.title.lowercase().contains(query.removePrefix("/"))
    }
}

private fun iconForSlashCommand(command: String): ImageVector = when (command) {
    "/briefing" -> Icons.Default.WbSunny
    "/budget" -> Icons.Default.AccountBalanceWallet
    "/vault" -> Icons.Default.FolderShared
    "/search" -> Icons.Default.Search
    "/playbook" -> Icons.Default.Description
    "/capabilities" -> Icons.Default.AutoAwesome
    "/trigger_weekly" -> Icons.Default.Psychology
    else -> Icons.Default.AutoAwesome
}

/**
 * Floating Command Palette docked cleanly above ChatInputBar whenever Tuan types `/`.
 */
@Composable
fun SlashCommandPaletteCard(
    commands: List<SlashCommandSpec>,
    onSelectCommand: (SlashCommandSpec) -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val surfaceBg = if (nexaColors.isDark) Color(0xFF0E1520) else MaterialTheme.colorScheme.surface

    Surface(
        color = surfaceBg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, nexaColors.cardBorder),
        tonalElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "COMMANDS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Tap to run",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
            }
            HorizontalDivider(thickness = 0.75.dp, color = nexaColors.cardBorder.copy(alpha = 0.6f))

            commands.forEachIndexed { idx, spec ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCommand(spec) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier.size(32.dp)
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = iconForSlashCommand(spec.command),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (spec.argHint.isNotEmpty()) "${spec.command} ${spec.argHint}" else spec.command,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = spec.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = spec.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (idx < commands.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = nexaColors.cardBorder.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
            }
        }
    }
}
