package com.nexa.mobilebridge.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.nexa.mobilebridge.core.security.findActivity
import com.nexa.mobilebridge.data.local.AccentPreset
import com.nexa.mobilebridge.data.local.ThemeMode

private fun buildDarkScheme(accent: AccentPreset): ColorScheme {
    val (primary, onPrimary, primaryContainer) = when (accent) {
        AccentPreset.CYAN -> Triple(NexaDarkCyan, NexaDarkOnCyan, NexaDarkCyanContainer)
        AccentPreset.EMERALD -> Triple(NexaDarkEmerald, NexaDarkOnEmerald, NexaDarkEmeraldCont)
        AccentPreset.AMBER -> Triple(NexaDarkAmber, NexaDarkOnAmber, NexaDarkAmberCont)
        AccentPreset.VIOLET -> Triple(NexaDarkViolet, NexaDarkOnViolet, NexaDarkVioletCont)
    }
    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = primary,
        secondary = NexaDarkViolet,
        tertiary = NexaDarkEmerald,
        background = NexaDarkBg,
        onBackground = NexaDarkOnSurface,
        surface = NexaDarkSurface,
        onSurface = NexaDarkOnSurface,
        surfaceVariant = NexaDarkSurfaceVariant,
        onSurfaceVariant = NexaDarkOnSurfaceDim,
        outline = NexaDarkOutline,
        error = NexaDarkCrimson,
        onError = NexaDarkOnCrimson
    )
}

private fun buildLightScheme(accent: AccentPreset): ColorScheme {
    val (primary, onPrimary, primaryContainer) = when (accent) {
        AccentPreset.CYAN -> Triple(NexaLightCyan, NexaLightOnCyan, NexaLightCyanContainer)
        AccentPreset.EMERALD -> Triple(NexaLightEmerald, NexaLightOnEmerald, NexaLightEmeraldCont)
        AccentPreset.AMBER -> Triple(NexaLightAmber, NexaLightOnAmber, NexaLightAmberCont)
        AccentPreset.VIOLET -> Triple(NexaLightViolet, NexaLightOnViolet, NexaLightVioletCont)
    }
    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = primary,
        secondary = NexaLightViolet,
        tertiary = NexaLightEmerald,
        background = NexaLightBg,
        onBackground = NexaLightOnSurface,
        surface = NexaLightSurface,
        onSurface = NexaLightOnSurface,
        surfaceVariant = NexaLightSurfaceVariant,
        onSurfaceVariant = NexaLightOnSurfaceDim,
        outline = NexaLightOutline,
        error = NexaLightCrimson,
        onError = NexaLightOnCrimson
    )
}

@Composable
fun NexaMobileBridgeTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accentPreset: AccentPreset = AccentPreset.CYAN,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (isDark) buildDarkScheme(accentPreset) else buildLightScheme(accentPreset)
    val semanticColors = if (isDark) {
        darkSemanticColorsFor(accentPreset)
    } else {
        lightSemanticColorsFor(accentPreset)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(activity.window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(LocalNexaColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexaTypography,
            content = content
        )
    }
}
