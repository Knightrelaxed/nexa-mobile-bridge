package com.nexa.mobilebridge.ui.components

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nexa.mobilebridge.NexaMainTab
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.data.chat.NexaAvatarState
import com.nexa.mobilebridge.theme.LocalNexaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * Unified visual eye poses for the mini chat visor, 1:1 with NexaExpressiveEyes (Live Call):
 * - IDLE / LISTENING: Cyan (#64FFDA) vertical stadium capsules (0 FPS between organic blinks)
 * - THINKING: Amber Gold (#F59E0B) horizontal visor + internal white scanline (Live Call PROCESSING)
 * - STREAMING: Electric Blue (#38BDF8) vertical capsules with soft rhythmic squash-and-stretch (Live Call SPEAKING)
 * - STEP_UP: Amber Gold (#F59E0B) focused vertical capsules + subtle pulsing visor border
 * - ERROR: Orange Coral (#FB923C) tilted puzzled eyes (-10/+8 deg) + mini AMOLED dark pupils (Live Call SLOW_NETWORK)
 * - RECONNECTING: Crimson (#EF4444) horizontal visor + scanline + warning pulse (Live Call RECONNECTING)
 * - OFFLINE: Slate Gray (#94A3B8) closed sleep line, 0 FPS (Live Call DISCONNECTING)
 */
enum class NexaVisorVisualState {
    IDLE,
    LISTENING,
    THINKING,
    STREAMING,
    STEP_UP,
    ERROR,
    RECONNECTING,
    OFFLINE
}

/**
 * Pure priority resolver mapping chat avatar state + WebSocket connection state into a single visor pose.
 */
fun resolveVisorVisualState(
    avatarState: NexaAvatarState,
    connectionState: ConnectionState
): NexaVisorVisualState = when (connectionState) {
    is ConnectionState.Disconnected,
    is ConnectionState.AuthFailed -> NexaVisorVisualState.OFFLINE
    is ConnectionState.Connecting,
    is ConnectionState.Reconnecting -> NexaVisorVisualState.RECONNECTING
    is ConnectionState.Connected -> when (avatarState) {
        NexaAvatarState.STEP_UP -> NexaVisorVisualState.STEP_UP
        NexaAvatarState.ALERT -> NexaVisorVisualState.ERROR
        NexaAvatarState.THINKING -> NexaVisorVisualState.THINKING
        NexaAvatarState.STREAMING -> NexaVisorVisualState.STREAMING
        NexaAvatarState.LISTENING -> NexaVisorVisualState.LISTENING
        NexaAvatarState.IDLE -> NexaVisorVisualState.IDLE
    }
}

private val VisorGlassDark = Color(0xFF0D141C)
private val VisorPupilDark = Color(0xFF06090E)
private val VisorColorCyan = Color(0xFF64FFDA)
private val VisorColorAmber = Color(0xFFF59E0B)
private val VisorColorBlue = Color(0xFF38BDF8)
private val VisorColorCoral = Color(0xFFFB923C)
private val VisorColorCrimson = Color(0xFFEF4444)
private val VisorColorSlate = Color(0xFF94A3B8)

/**
 * 48x38 dp N.E.X.A Mini OLED Robot Visor aligned 1:1 with NexaExpressiveEyes (Live Call).
 * Pure eyes only (no mouth), dark OLED visor glass in both Light and Dark themes,
 * fluid spring morphing, tactile tap-to-wink, and zero continuous frame callbacks in IDLE/OFFLINE.
 */
@Composable
fun NexaVisorBadge(
    visualState: NexaVisorVisualState,
    modifier: Modifier = Modifier,
    voiceLevelFlow: StateFlow<Float>? = null,
    hapticEnabled: Boolean = true
) {
    val nexaColors = LocalNexaColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val reduceMotion = remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    // Collect live voice amplitude ONLY while LISTENING so idle/chat never observes mic ticks
    var liveVoiceLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lifecycleOwner, visualState, voiceLevelFlow) {
        if (visualState == NexaVisorVisualState.LISTENING && voiceLevelFlow != null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                voiceLevelFlow.collect { level ->
                    liveVoiceLevel = level.coerceIn(0f, 1f)
                }
            }
        } else {
            liveVoiceLevel = 0f
        }
    }
    val smoothVoiceLevel by animateFloatAsState(
        targetValue = if (reduceMotion) 0f else liveVoiceLevel,
        animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing),
        label = "visorVoiceLevel"
    )

    // 1. Decoupled organic blink & micro-saccade (0 FPS between blinks, runs only in IDLE / LISTENING)
    val blinkScale = remember { Animatable(1f) }
    val leftTapBlink = remember { Animatable(1f) }
    val rightTapBlink = remember { Animatable(1f) }
    val saccadeX = remember { Animatable(0f) }

    LaunchedEffect(lifecycleOwner, visualState, reduceMotion) {
        if (reduceMotion) {
            blinkScale.snapTo(1f)
            saccadeX.snapTo(0f)
            return@LaunchedEffect
        }
        val canBlink = visualState == NexaVisorVisualState.IDLE || visualState == NexaVisorVisualState.LISTENING
        if (!canBlink) {
            blinkScale.animateTo(1f, tween(120))
            saccadeX.animateTo(0f, tween(160))
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val waitMs = Random.nextLong(3_400L, 5_600L)
                delay(waitMs)
                if (liveVoiceLevel > 0.05f) continue

                // Subtle organic saccade before blinking (only in IDLE so LISTENING stays focused on input)
                if (visualState == NexaVisorVisualState.IDLE) {
                    val targetShift = (Random.nextFloat() * 2.4f) - 1.2f
                    saccadeX.animateTo(targetShift, tween(240, easing = FastOutSlowInEasing))
                } else {
                    saccadeX.animateTo(0f, tween(180))
                }

                // Organic blink curve: fast close 65ms -> 20ms hold -> spring open
                blinkScale.animateTo(0.08f, tween(65, easing = FastOutSlowInEasing))
                delay(20L)
                blinkScale.animateTo(
                    1f,
                    spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh)
                )

                // Occasional subtle double-blink (~16%) for natural life
                if (Random.nextFloat() < 0.16f && liveVoiceLevel <= 0.05f) {
                    delay(120L)
                    blinkScale.animateTo(0.08f, tween(60, easing = FastOutSlowInEasing))
                    delay(18L)
                    blinkScale.animateTo(
                        1f,
                        spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessHigh)
                    )
                }
            }
        }
    }

    // 2. Dynamic state oscillators (active ONLY during THINKING, STREAMING, STEP_UP, RECONNECTING -> 0 FPS at rest)
    val scanShimmer = remember { Animatable(-1f) }
    val streamWave = remember { Animatable(0f) }
    val pulseAlpha = remember { Animatable(1f) }

    LaunchedEffect(lifecycleOwner, visualState, reduceMotion) {
        if (reduceMotion) {
            scanShimmer.snapTo(0f)
            streamWave.snapTo(0f)
            pulseAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (visualState) {
                NexaVisorVisualState.THINKING -> {
                    streamWave.snapTo(0f)
                    pulseAlpha.snapTo(1f)
                    while (isActive) {
                        scanShimmer.snapTo(-1f)
                        scanShimmer.animateTo(1f, tween(1_400, easing = LinearEasing))
                    }
                }
                NexaVisorVisualState.RECONNECTING -> {
                    streamWave.snapTo(0f)
                    launch {
                        while (isActive) {
                            scanShimmer.snapTo(-1f)
                            scanShimmer.animateTo(1f, tween(1_400, easing = LinearEasing))
                        }
                    }
                    while (isActive) {
                        pulseAlpha.animateTo(0.42f, tween(420, easing = FastOutSlowInEasing))
                        pulseAlpha.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                    }
                }
                NexaVisorVisualState.STREAMING -> {
                    scanShimmer.snapTo(0f)
                    pulseAlpha.snapTo(1f)
                    while (isActive) {
                        streamWave.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                        streamWave.animateTo(0.18f, tween(200, easing = FastOutSlowInEasing))
                    }
                }
                NexaVisorVisualState.STEP_UP -> {
                    scanShimmer.snapTo(0f)
                    streamWave.snapTo(0f)
                    while (isActive) {
                        pulseAlpha.animateTo(0.48f, tween(650, easing = FastOutSlowInEasing))
                        pulseAlpha.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
                    }
                }
                else -> {
                    scanShimmer.snapTo(0f)
                    streamWave.animateTo(0f, tween(140))
                    pulseAlpha.animateTo(1f, tween(140))
                }
            }
        }
    }

    // 3. Target geometries matching NexaExpressiveEyes (scaled cleanly to 48x38 dp)
    val talkBoost = if (visualState == NexaVisorVisualState.STREAMING) streamWave.value else 0f
    val voiceBoost = if (visualState == NexaVisorVisualState.LISTENING) smoothVoiceLevel else 0f

    val targetLeftW = when (visualState) {
        NexaVisorVisualState.IDLE -> 9.2f
        NexaVisorVisualState.LISTENING -> 9.4f - (voiceBoost * 0.8f)
        NexaVisorVisualState.THINKING -> 13.6f
        NexaVisorVisualState.STREAMING -> 9.2f - (talkBoost * 1.0f)
        NexaVisorVisualState.STEP_UP -> 9.6f
        NexaVisorVisualState.ERROR -> 10.2f
        NexaVisorVisualState.RECONNECTING -> 12.6f
        NexaVisorVisualState.OFFLINE -> 9.6f
    }
    val targetLeftH = when (visualState) {
        NexaVisorVisualState.IDLE -> 13.4f
        NexaVisorVisualState.LISTENING -> 13.8f + (voiceBoost * 3.6f)
        NexaVisorVisualState.THINKING -> 4.6f
        NexaVisorVisualState.STREAMING -> 12.4f + (talkBoost * 4.4f)
        NexaVisorVisualState.STEP_UP -> 14.0f
        NexaVisorVisualState.ERROR -> 7.2f
        NexaVisorVisualState.RECONNECTING -> 4.2f
        NexaVisorVisualState.OFFLINE -> 1.8f
    }
    val targetRightW = when (visualState) {
        NexaVisorVisualState.ERROR -> 9.8f
        else -> targetLeftW
    }
    val targetRightH = when (visualState) {
        NexaVisorVisualState.ERROR -> 7.8f
        else -> targetLeftH
    }
    val targetLeftRot = if (visualState == NexaVisorVisualState.ERROR) -10f else 0f
    val targetRightRot = if (visualState == NexaVisorVisualState.ERROR) 8f else 0f
    val targetOffsetY = when (visualState) {
        NexaVisorVisualState.LISTENING -> 2.1f - (voiceBoost * 0.6f)
        NexaVisorVisualState.THINKING,
        NexaVisorVisualState.RECONNECTING,
        NexaVisorVisualState.OFFLINE -> 0.8f
        NexaVisorVisualState.STREAMING -> -(talkBoost * 0.9f)
        NexaVisorVisualState.ERROR -> 0.4f
        NexaVisorVisualState.IDLE,
        NexaVisorVisualState.STEP_UP -> 0f
    }
    val targetPupilR = if (visualState == NexaVisorVisualState.ERROR) 1.75f else 0f

    val morphSpring = spring<Float>(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow)
    val fastTween = tween<Float>(durationMillis = 70, easing = FastOutSlowInEasing)
    val activeElastic = visualState == NexaVisorVisualState.STREAMING || voiceBoost > 0.01f

    val leftW by animateFloatAsState(targetLeftW, if (activeElastic) fastTween else morphSpring, label = "miniLW")
    val leftH by animateFloatAsState(targetLeftH, if (activeElastic) fastTween else morphSpring, label = "miniLH")
    val rightW by animateFloatAsState(targetRightW, if (activeElastic) fastTween else morphSpring, label = "miniRW")
    val rightH by animateFloatAsState(targetRightH, if (activeElastic) fastTween else morphSpring, label = "miniRH")
    val leftRot by animateFloatAsState(targetLeftRot, morphSpring, label = "miniLRot")
    val rightRot by animateFloatAsState(targetRightRot, morphSpring, label = "miniRRot")
    val offsetY by animateFloatAsState(targetOffsetY, if (activeElastic) fastTween else morphSpring, label = "miniOY")
    val pupilR by animateFloatAsState(targetPupilR, morphSpring, label = "miniPR")

    val targetCoreColor = when (visualState) {
        NexaVisorVisualState.IDLE,
        NexaVisorVisualState.LISTENING -> VisorColorCyan
        NexaVisorVisualState.THINKING,
        NexaVisorVisualState.STEP_UP -> VisorColorAmber
        NexaVisorVisualState.STREAMING -> VisorColorBlue
        NexaVisorVisualState.ERROR -> VisorColorCoral
        NexaVisorVisualState.RECONNECTING -> VisorColorCrimson
        NexaVisorVisualState.OFFLINE -> VisorColorSlate
    }
    val targetGlowAlpha = when (visualState) {
        NexaVisorVisualState.OFFLINE -> 0.16f
        NexaVisorVisualState.STREAMING -> 0.34f
        NexaVisorVisualState.RECONNECTING -> 0.38f
        else -> 0.28f
    }
    val coreColor by animateColorAsState(
        targetValue = targetCoreColor,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "miniCoreColor"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = targetGlowAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "miniGlowAlpha"
    )

    val restingBorder = if (nexaColors.isDark) nexaColors.cardBorder else Color(0xFF233041)
    val targetBorderColor = when (visualState) {
        NexaVisorVisualState.STEP_UP -> VisorColorAmber.copy(alpha = (0.45f + pulseAlpha.value * 0.50f).coerceIn(0f, 1f))
        NexaVisorVisualState.THINKING -> VisorColorAmber.copy(alpha = 0.50f)
        NexaVisorVisualState.ERROR -> VisorColorCoral.copy(alpha = 0.65f)
        NexaVisorVisualState.RECONNECTING -> VisorColorCrimson.copy(alpha = (0.35f + pulseAlpha.value * 0.55f).coerceIn(0f, 1f))
        else -> restingBorder
    }
    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(durationMillis = 220),
        label = "miniBorderColor"
    )

    val eyeAlpha = if (visualState == NexaVisorVisualState.RECONNECTING) pulseAlpha.value else 1f
    val isVisorScanMode =
        visualState == NexaVisorVisualState.THINKING || visualState == NexaVisorVisualState.RECONNECTING
    val canTapWink = !reduceMotion && visualState != NexaVisorVisualState.OFFLINE && !isVisorScanMode

    Surface(
        color = VisorGlassDark,
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(width = 1.dp, color = borderColor),
        modifier = modifier
            .size(width = 48.dp, height = 38.dp)
            .pointerInput(canTapWink, hapticEnabled) {
                if (!canTapWink) return@pointerInput
                detectTapGestures(
                    onTap = { tapOffset ->
                        if (hapticEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        val targetAnim = if (tapOffset.x < size.width / 2f) leftTapBlink else rightTapBlink
                        coroutineScope.launch {
                            targetAnim.animateTo(0.08f, tween(55, easing = FastOutSlowInEasing))
                            targetAnim.animateTo(
                                1f,
                                spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
                            )
                        }
                    }
                )
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = eyeAlpha }
        ) {
            val scale = minOf(size.width / 48.dp.toPx(), size.height / 38.dp.toPx()).coerceAtLeast(0.5f)
            val centerX = size.width / 2f
            val centerY = size.height / 2f + (offsetY.dp.toPx() * scale)
            val spacing = 8.4.dp.toPx() * scale
            val saccadePx = saccadeX.value.dp.toPx() * scale
            val leftCenterX = centerX - spacing + saccadePx
            val rightCenterX = centerX + spacing + saccadePx
            val glowColor = coreColor.copy(alpha = glowAlpha)

            drawMiniExpressiveEye(
                centerX = leftCenterX,
                centerY = centerY,
                width = leftW.dp.toPx() * scale,
                height = leftH.dp.toPx() * scale,
                rotationDegrees = leftRot,
                pupilRadius = pupilR.dp.toPx() * scale,
                coreColor = coreColor,
                glowColor = glowColor,
                blinkScaleY = (blinkScale.value * leftTapBlink.value).coerceIn(0.06f, 1.2f),
                showScanline = isVisorScanMode && !reduceMotion,
                scanShimmer = scanShimmer.value
            )

            drawMiniExpressiveEye(
                centerX = rightCenterX,
                centerY = centerY,
                width = rightW.dp.toPx() * scale,
                height = rightH.dp.toPx() * scale,
                rotationDegrees = rightRot,
                pupilRadius = pupilR.dp.toPx() * scale,
                coreColor = coreColor,
                glowColor = glowColor,
                blinkScaleY = (blinkScale.value * rightTapBlink.value).coerceIn(0.06f, 1.2f),
                showScanline = isVisorScanMode && !reduceMotion,
                scanShimmer = scanShimmer.value
            )
        }
    }
}

private fun DrawScope.drawMiniExpressiveEye(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    rotationDegrees: Float,
    pupilRadius: Float,
    coreColor: Color,
    glowColor: Color,
    blinkScaleY: Float,
    showScanline: Boolean,
    scanShimmer: Float
) {
    if (width < 1f || height < 1f) return

    rotate(degrees = rotationDegrees, pivot = Offset(centerX, centerY)) {
        val effectiveHeight = (height * blinkScaleY).coerceAtLeast(1.2.dp.toPx())
        val left = centerX - (width / 2f)
        val top = centerY - (effectiveHeight / 2f)
        val radiusPx = (width / 2f).coerceAtMost(effectiveHeight / 2f)
        val corner = CornerRadius(radiusPx, radiusPx)

        // Layer 1: Lightweight OLED outer bloom glow (matches Live Call +18% padding)
        val padX = width * 0.18f
        val padY = effectiveHeight * 0.18f
        val glowW = width + padX * 2f
        val glowH = effectiveHeight + padY * 2f
        val glowRadius = (glowW / 2f).coerceAtMost(glowH / 2f)
        drawRoundRect(
            color = glowColor,
            topLeft = Offset(left - padX, top - padY),
            size = Size(glowW, glowH),
            cornerRadius = CornerRadius(glowRadius, glowRadius)
        )

        // Layer 2: Solid neon OLED eye core
        drawRoundRect(
            color = coreColor,
            topLeft = Offset(left, top),
            size = Size(width, effectiveHeight),
            cornerRadius = corner
        )

        // Layer 3: Mini AMOLED dark pupil cutout (ERROR / Live Call SLOW_NETWORK)
        if (pupilRadius > 0.8f && effectiveHeight > pupilRadius * 2.1f) {
            val pupilYOffset = -((effectiveHeight / 2f) - pupilRadius - 1f).coerceAtLeast(0f) * 0.15f
            val pupilCenter = Offset(centerX, centerY + pupilYOffset)
            drawCircle(
                color = VisorPupilDark,
                radius = pupilRadius,
                center = pupilCenter
            )
            drawCircle(
                color = coreColor.copy(alpha = 0.55f),
                radius = pupilRadius,
                center = pupilCenter,
                style = Stroke(width = 0.7.dp.toPx())
            )
        }

        // Layer 4: Internal white scanning line (THINKING / RECONNECTING visor mode)
        if (showScanline && width > effectiveHeight * 1.4f) {
            val shimmerX = centerX + (scanShimmer * (width * 0.38f))
            val scanAlpha = (1f - abs(scanShimmer) * 0.35f).coerceIn(0.45f, 0.88f)
            val padInset = (effectiveHeight * 0.16f).coerceAtLeast(0.8f)
            drawLine(
                color = Color.White.copy(alpha = scanAlpha),
                start = Offset(shimmerX, top + padInset),
                end = Offset(shimmerX, top + effectiveHeight - padInset),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Uniform Top App Bar matching Stitch Blueprint v2 (01_chat_ai_native_v2 & 02_hud_bridge_control_v2).
 * Shows 48x38 dp Mini OLED Visor synced with Live Call eye language, "N.E.X.A" + connection status,
 * optional Voice Call quick action, and 48 dp Settings button.
 */
@Composable
fun NexaTopBar(
    activeTab: NexaMainTab,
    connectionState: ConnectionState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    avatarState: NexaAvatarState = NexaAvatarState.IDLE,
    voiceLevelFlow: StateFlow<Float>? = null,
    hapticEnabled: Boolean = true,
    onStartVoiceCall: (() -> Unit)? = null
) {
    val nexaColors = LocalNexaColors.current
    val (statusColor: Color, statusIcon: ImageVector, statusText: String) = when (connectionState) {
        is ConnectionState.Connected -> Triple(
            nexaColors.emerald,
            Icons.Default.CheckCircle,
            if (activeTab == NexaMainTab.CHAT) "Terhubung (TLS 1.3)" else "Terhubung · TLS"
        )
        is ConnectionState.Connecting -> Triple(nexaColors.amber, Icons.Default.Sync, "Menyambungkan...")
        is ConnectionState.Reconnecting -> Triple(nexaColors.amber, Icons.Default.Sync, "Mencoba ulang #${connectionState.attempt}")
        is ConnectionState.Disconnected -> Triple(nexaColors.crimson, Icons.Default.CloudOff, "Terputus")
        is ConnectionState.AuthFailed -> Triple(nexaColors.crimson, Icons.Default.ErrorOutline, "Gagal Otentikasi")
    }

    val visorState = resolveVisorVisualState(avatarState = avatarState, connectionState = connectionState)

    val subtitleLabel = when {
        activeTab == NexaMainTab.CHAT && visorState == NexaVisorVisualState.THINKING -> "Sedang mengerjakan..."
        activeTab == NexaMainTab.CHAT && visorState == NexaVisorVisualState.STREAMING -> "Sedang menjawab..."
        activeTab == NexaMainTab.CHAT && visorState == NexaVisorVisualState.STEP_UP -> "Menunggu verifikasi sidik jari..."
        activeTab == NexaMainTab.CHAT && visorState == NexaVisorVisualState.LISTENING -> "Menyimak..."
        activeTab == NexaMainTab.CHAT -> statusText
        else -> "$statusText · ${activeTab.label.uppercase()}"
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(62.dp)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NexaVisorBadge(
                        visualState = visorState,
                        voiceLevelFlow = voiceLevelFlow,
                        hapticEnabled = hapticEnabled
                    )

                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = "N.E.X.A",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = statusText,
                                tint = statusColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = subtitleLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onStartVoiceCall != null && (activeTab == NexaMainTab.CHAT || activeTab == NexaMainTab.HUD)) {
                        IconButton(
                            onClick = onStartVoiceCall,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Panggilan Suara Langsung N.E.X.A",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Pengaturan dan Keamanan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = nexaColors.cardBorder.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Uniform 4-Tab Bottom Navigation Bar (CHAT | HUD | LOGS | SHELL) with consistent 2dp top indicator bar
 * and >= 48dp touch targets (PRD 12.1 / Stitch Blueprint v2).
 */
@Composable
fun NexaBottomNavBar(
    activeTab: NexaMainTab,
    onSelectTab: (NexaMainTab) -> Unit,
    modifier: Modifier = Modifier,
    chatUnreadCount: Int = 0
) {
    val nexaColors = LocalNexaColors.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(
                thickness = 1.dp,
                color = nexaColors.cardBorder
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NexaMainTab.entries.forEach { tab ->
                    val isSelected = tab == activeTab
                    val activeTint = MaterialTheme.colorScheme.primary
                    val inactiveTint = MaterialTheme.colorScheme.onSurfaceVariant
                    val tint = if (isSelected) activeTint else inactiveTint
                    val showBadge = tab == NexaMainTab.CHAT && chatUnreadCount > 0 && !isSelected

                    val icon = when (tab) {
                        NexaMainTab.CHAT -> if (isSelected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
                        NexaMainTab.HUD -> if (isSelected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
                        NexaMainTab.LOGS -> if (isSelected) Icons.AutoMirrored.Filled.ReceiptLong else Icons.AutoMirrored.Outlined.ReceiptLong
                        NexaMainTab.SHELL -> if (isSelected) Icons.Filled.Terminal else Icons.Outlined.Terminal
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .semantics {
                                selected = isSelected
                                contentDescription = if (showBadge) "${tab.label} ($chatUnreadCount belum dibaca)" else tab.label
                            }
                            .clickable(
                                role = Role.Tab,
                                onClick = { onSelectTab(tab) }
                            ),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .width(34.dp)
                                .height(2.dp)
                                .background(
                                    color = if (isSelected) activeTint else Color.Transparent,
                                    shape = RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)
                                )
                        )

                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(top = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(22.dp)
                                )
                                if (showBadge) {
                                    val badgeLabel = if (chatUnreadCount > 99) "99+" else chatUnreadCount.toString()
                                    Box(
                                        modifier = Modifier
                                            .offset(x = 8.dp, y = (-5).dp)
                                            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                            .background(color = nexaColors.crimson, shape = CircleShape)
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = badgeLabel,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            lineHeight = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = tab.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = tint
                            )
                        }
                    }
                }
            }
        }
    }
}
