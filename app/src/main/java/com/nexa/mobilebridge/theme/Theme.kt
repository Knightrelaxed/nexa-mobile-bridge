package com.nexa.mobilebridge.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = NexaCyan,
    onPrimary = Color(0xFF003640), // Defined inline for contrast
    primaryContainer = NexaCyanDim,
    secondary = NexaViolet,
    background = NexaBg,
    surface = NexaSurface,
    surfaceVariant = NexaSurface2,
    onSurface = NexaOnSurface,
    onSurfaceVariant = NexaOnSurfaceDim,
    error = NexaRed
)

// The PRD specifies forcing Dark Mode
@Composable
fun NexaMobileBridgeTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkColorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = NexaTypography,
        content = content
    )
}
