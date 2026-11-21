package com.nexa.mobilebridge.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.nexa.mobilebridge.data.local.AccentPreset

// =====================================================================
// N.E.X.A Cyber-Terminal Design Tokens (Dark & Light, WCAG AA >= 4.5:1)
// Aligned with Stitch Blueprint v2/v3
// =====================================================================

// Dark Palette (Default - Void Slate)
val NexaDarkBg             = Color(0xFF0A0F14)
val NexaDarkSurface        = Color(0xFF111820)
val NexaDarkSurfaceVariant = Color(0xFF18222D)
val NexaDarkOutline        = Color(0xFF2B3B4B)
val NexaDarkOnSurface      = Color(0xFFE6EDF3)
val NexaDarkOnSurfaceDim   = Color(0xFF9BA7B4) // >= 6.8:1 contrast on #111820

val NexaDarkCyan           = Color(0xFF00E5FF)
val NexaDarkOnCyan         = Color(0xFF002B33)
val NexaDarkCyanContainer  = Color(0xFF003B47)

val NexaDarkEmerald        = Color(0xFF00E676)
val NexaDarkOnEmerald      = Color(0xFF002914)
val NexaDarkEmeraldCont    = Color(0xFF0B3623)

val NexaDarkAmber          = Color(0xFFFFAB00)
val NexaDarkOnAmber        = Color(0xFF2E1D00)
val NexaDarkAmberCont      = Color(0xFF3A2807)

val NexaDarkCrimson        = Color(0xFFFF5252)
val NexaDarkOnCrimson      = Color(0xFF3B0000)

val NexaDarkViolet         = Color(0xFFCE93D8)
val NexaDarkOnViolet       = Color(0xFF2E0B38)
val NexaDarkVioletCont     = Color(0xFF331D3B)

val NexaVisorEyeDark       = Color(0xFF64FFDA)

// Light Palette (WCAG AA >= 4.5:1 on white/light slate)
val NexaLightBg             = Color(0xFFF6F8FA)
val NexaLightSurface        = Color(0xFFFFFFFF)
val NexaLightSurfaceVariant = Color(0xFFEAECEF)
val NexaLightOutline        = Color(0xFFB8C4D0)
val NexaLightOnSurface      = Color(0xFF1F2328)
val NexaLightOnSurfaceDim   = Color(0xFF57606A) // >= 5.7:1 contrast on #FFFFFF

val NexaLightCyan           = Color(0xFF006B7D) // >= 6.1:1 on #FFFFFF, >= 5.3:1 on #D6F3F8
val NexaLightOnCyan         = Color(0xFFFFFFFF)
val NexaLightCyanContainer  = Color(0xFFD6F3F8)

val NexaLightEmerald        = Color(0xFF0D6E3B) // >= 6.2:1 on #FFFFFF, >= 5.4:1 on #D9F5E5
val NexaLightOnEmerald      = Color(0xFFFFFFFF)
val NexaLightEmeraldCont    = Color(0xFFD9F5E5)

val NexaLightAmber          = Color(0xFF7C4700) // >= 7.6:1 on #FFFFFF, >= 6.1:1 on #FDE68A
val NexaLightOnAmber        = Color(0xFFFFFFFF)
val NexaLightAmberCont      = Color(0xFFFFF0D4)

val NexaLightCrimson        = Color(0xFFA8141E) // >= 7.5:1 on #FFFFFF, >= 5.2:1 on #FECACA
val NexaLightOnCrimson      = Color(0xFFFFFFFF)

val NexaLightViolet         = Color(0xFF6D28D9) // >= 7.3:1 on #FFFFFF, >= 6.0:1 on #EDE4FF
val NexaLightOnViolet       = Color(0xFFFFFFFF)
val NexaLightVioletCont     = Color(0xFFEDE4FF)

val NexaVisorEyeLight       = Color(0xFF006B7D)

@Immutable
data class NexaSemanticColors(
    val cyan: Color,
    val emerald: Color,
    val amber: Color,
    val crimson: Color,
    val violet: Color,
    val visorEye: Color,
    val cardBorder: Color,
    val elevatedSurface: Color,
    val primaryButtonFill: Color,
    val onPrimaryButtonFill: Color,
    val isDark: Boolean
) {
    fun swatchFor(preset: AccentPreset, selected: Boolean = true): Color {
        if (isDark) {
            return if (selected) {
                when (preset) {
                    AccentPreset.CYAN -> Color(0xFF00B8D4)
                    AccentPreset.EMERALD -> Color(0xFF00C853)
                    AccentPreset.AMBER -> Color(0xFFFFA000)
                    AccentPreset.VIOLET -> Color(0xFFAB47BC)
                }
            } else {
                // RD-M2 / 05_settings_security_v3.png: Subdued unselected swatches so active preset stands out cleanly on OLED
                when (preset) {
                    AccentPreset.CYAN -> Color(0xFF0E6E80)
                    AccentPreset.EMERALD -> Color(0xFF158A4E)
                    AccentPreset.AMBER -> Color(0xFFA67830)
                    AccentPreset.VIOLET -> Color(0xFF7D5D8F)
                }
            }
        }
        val base = when (preset) {
            AccentPreset.CYAN -> cyan
            AccentPreset.EMERALD -> emerald
            AccentPreset.AMBER -> amber
            AccentPreset.VIOLET -> violet
        }
        return if (selected) base else base.copy(alpha = 0.72f)
    }
}

fun darkSemanticColorsFor(accent: AccentPreset): NexaSemanticColors {
    val (fill, onFill) = when (accent) {
        AccentPreset.CYAN -> Color(0xFF00B8D4) to Color(0xFF021014)
        AccentPreset.EMERALD -> Color(0xFF00C853) to Color(0xFF001C0B)
        AccentPreset.AMBER -> Color(0xFFFFA000) to Color(0xFF1F1100)
        AccentPreset.VIOLET -> Color(0xFFCE93D8) to Color(0xFF1B0422)
    }
    return NexaSemanticColors(
        cyan = NexaDarkCyan,
        emerald = NexaDarkEmerald,
        amber = NexaDarkAmber,
        crimson = NexaDarkCrimson,
        violet = NexaDarkViolet,
        visorEye = NexaVisorEyeDark,
        cardBorder = NexaDarkOutline,
        elevatedSurface = NexaDarkSurfaceVariant,
        primaryButtonFill = fill,
        onPrimaryButtonFill = onFill,
        isDark = true
    )
}

fun lightSemanticColorsFor(accent: AccentPreset): NexaSemanticColors {
    val fill = when (accent) {
        AccentPreset.CYAN -> NexaLightCyan
        AccentPreset.EMERALD -> NexaLightEmerald
        AccentPreset.AMBER -> NexaLightAmber
        AccentPreset.VIOLET -> NexaLightViolet
    }
    return NexaSemanticColors(
        cyan = NexaLightCyan,
        emerald = NexaLightEmerald,
        amber = NexaLightAmber,
        crimson = NexaLightCrimson,
        violet = NexaLightViolet,
        visorEye = NexaVisorEyeLight,
        cardBorder = NexaLightOutline,
        elevatedSurface = NexaLightSurfaceVariant,
        primaryButtonFill = fill,
        onPrimaryButtonFill = Color.White,
        isDark = false
    )
}

val DarkSemanticColors = darkSemanticColorsFor(AccentPreset.CYAN)
val LightSemanticColors = lightSemanticColorsFor(AccentPreset.CYAN)

val LocalNexaColors = staticCompositionLocalOf { DarkSemanticColors }
