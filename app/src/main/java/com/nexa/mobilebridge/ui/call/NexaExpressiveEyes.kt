package com.nexa.mobilebridge.ui.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * NexaExpressiveEyes
 *
 * Vector-rendered Cybernetic OLED Robot Eyes inspired by EMO & Vector robots.
 * Fully continuous fluid morphing across all 6 Call States in Jetpack Compose Canvas.
 *
 * Key design principles:
 *   - PURE EYES ONLY (no mouth/smile on any state including Mentransmisikan)
 *   - Fluid vector spring morphing, NOT discrete frame/sprite swaps
 *   - Asymmetric emerald thinking pose (user's explicit design choice)
 *   - Audio-reactive squash-and-stretch bounce on SPEAKING
 *   - Decoupled autonomous natural blink engine
 */
@Composable
fun NexaExpressiveEyes(
    hudState: LiveVoiceHudState,
    isSpeaking: Boolean,
    audioEnergy: Float = 0f,
    onEyeTapped: (target: String, tapCount: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // ─── 1. Autonomous Decoupled Blink Physics Engine ─────────────────────────────
    // Uses Animatable for precise frame-aligned blink animation instead of
    // System.currentTimeMillis() busy-loop which wastes CPU and is imprecise.
    val blinkAnimatable = remember { Animatable(1f) }
    val leftTapBlink = remember { Animatable(1f) }
    val rightTapBlink = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var eyeSaccadeX by remember { mutableFloatStateOf(0f) }

    // Smooth the saccade micro-shift so eyes glide rather than snap
    val animatedSaccadeX by animateFloatAsState(
        targetValue = eyeSaccadeX,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "SaccadeX"
    )

    LaunchedEffect(Unit) {
        // This runs independently of hudState changes so blinks don't restart
        // when state transitions (which would look unnatural)
        while (isActive) {
            val nextBlinkInterval = Random.nextLong(3200, 5200)
            delay(nextBlinkInterval)

            // Only blink during listening, thinking, or slow network
            if (hudState == LiveVoiceHudState.LISTENING ||
                hudState == LiveVoiceHudState.THINKING ||
                hudState == LiveVoiceHudState.SLOW_NETWORK
            ) {
                // Natural Saccade Micro-Shift before blinking
                eyeSaccadeX = Random.nextFloat() * 6f - 3f

                // Fast Blink Down (70ms)
                blinkAnimatable.animateTo(
                    targetValue = 0.06f,
                    animationSpec = tween(70, easing = FastOutSlowInEasing)
                )

                // Hold closed briefly
                delay(20)

                // Springy Blink Up (90ms with slight overshoot)
                blinkAnimatable.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.6f,
                        stiffness = Spring.StiffnessHigh
                    )
                )
            }
        }
    }

    // ─── 2. Audio-Reactive Smooth Energy Tracking ──────────────────────────────────
    // The key fix: audioEnergy from VoiceStreamHandler is a @Volatile var
    // that Compose cannot observe. We poll it every frame in a LaunchedEffect
    // and smooth it with exponential moving average for buttery 60fps tracking.
    var smoothedAudioEnergy by remember { mutableFloatStateOf(0f) }
    var isCurrentlySpeaking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Continuously sample audioEnergy at frame rate for smooth animation
        while (isActive) {
            withFrameMillis { _ ->
                isCurrentlySpeaking = isSpeaking
                val target = if (isSpeaking) audioEnergy.coerceIn(0f, 1f) else 0f
                // Exponential moving average: quick attack (0.35), slower decay (0.12)
                val smoothingFactor = if (target > smoothedAudioEnergy) 0.35f else 0.12f
                smoothedAudioEnergy += (target - smoothedAudioEnergy) * smoothingFactor
            }
        }
    }

    // ─── 3. Continuous Organic Oscillators ─────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "EyeOscillators")

    // Subtle organic breathing pulse for LISTENING state
    val breathingOscillator by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Breathing"
    )

    // Speech syllable synthetic talk bounce (active during speaking)
    val talkOscillator by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(180, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TalkBounce"
    )

    // Reconnecting & Processing Scanner Shimmer
    val scanShimmer by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ScanShimmer"
    )

    // Warning Pulse for Reconnecting
    val warningPulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "WarningPulse"
    )

    // Thinking pupil gentle drift (organic look-around feel)
    val thinkingDrift by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ThinkingDrift"
    )

    // ─── 4. State-Driven Fluid Spring Physics Transitions ─────────────────────────
    val springSpec = spring<Float>(
        dampingRatio = 0.65f, // Smooth low-overshoot bounce
        stiffness = Spring.StiffnessMediumLow
    )
    val fastSpringSpec = spring<Float>(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMedium
    )

    // Effective speech bounce combining smoothed RMS + synthetic syllable oscillation
    val effectiveAudioEnergy = if (hudState == LiveVoiceHudState.SPEAKING) {
        (smoothedAudioEnergy * 0.7f + talkOscillator * 0.3f)
    } else 0f

    // Breathing amplitude for LISTENING
    val breatheAmplitude = if (hudState == LiveVoiceHudState.LISTENING) breathingOscillator * 3f else 0f

    // ── Left Eye Geometries ──
    val targetLeftWidth = when (hudState) {
        LiveVoiceHudState.LISTENING -> 58f + breatheAmplitude * 0.4f
        LiveVoiceHudState.THINKING -> 58f + (breathingOscillator * 2f)
        LiveVoiceHudState.PROCESSING -> 106f
        LiveVoiceHudState.SPEAKING -> 58f - (effectiveAudioEnergy * 4f)
        LiveVoiceHudState.SLOW_NETWORK -> 64f
        LiveVoiceHudState.RECONNECTING -> 90f
        LiveVoiceHudState.DISCONNECTING -> 60f
    }
    val targetLeftHeight = when (hudState) {
        LiveVoiceHudState.LISTENING -> 84f + breatheAmplitude
        LiveVoiceHudState.THINKING -> 82f + (breathingOscillator * 3f)
        LiveVoiceHudState.PROCESSING -> 26f
        LiveVoiceHudState.SPEAKING -> 76f + (effectiveAudioEnergy * 32f)
        LiveVoiceHudState.SLOW_NETWORK -> 44f
        LiveVoiceHudState.RECONNECTING -> 22f
        LiveVoiceHudState.DISCONNECTING -> 6f // Gentle eye close
    }
    val targetLeftCornerRadius = when (hudState) {
        LiveVoiceHudState.LISTENING -> 29f
        LiveVoiceHudState.THINKING -> 29f // Elegant symmetrical stadium capsule
        LiveVoiceHudState.PROCESSING -> 13f // Visor
        LiveVoiceHudState.SPEAKING -> 29f
        LiveVoiceHudState.SLOW_NETWORK -> 22f
        LiveVoiceHudState.RECONNECTING -> 11f
        LiveVoiceHudState.DISCONNECTING -> 3f
    }
    val targetLeftRotation = when (hudState) {
        LiveVoiceHudState.LISTENING -> 0f
        LiveVoiceHudState.THINKING -> 0f
        LiveVoiceHudState.PROCESSING -> 0f
        LiveVoiceHudState.SPEAKING -> 0f
        LiveVoiceHudState.SLOW_NETWORK -> -10f // Droop puzzled
        LiveVoiceHudState.RECONNECTING -> 0f
        LiveVoiceHudState.DISCONNECTING -> 0f
    }
    val targetLeftOffsetY = when (hudState) {
        LiveVoiceHudState.LISTENING -> 0f
        LiveVoiceHudState.THINKING -> -4f
        LiveVoiceHudState.PROCESSING -> 6f
        LiveVoiceHudState.SPEAKING -> -(effectiveAudioEnergy * 3f) // Subtle vertical bounce
        LiveVoiceHudState.SLOW_NETWORK -> 2f
        LiveVoiceHudState.RECONNECTING -> 6f
        LiveVoiceHudState.DISCONNECTING -> 4f
    }
    val targetLeftPupilRadius = when (hudState) {
        LiveVoiceHudState.THINKING -> 0f // Pure solid OLED glowing capsule
        LiveVoiceHudState.SLOW_NETWORK -> 11f
        else -> 0f
    }
    val targetLeftPupilX = when (hudState) {
        LiveVoiceHudState.THINKING -> 0f
        else -> 0f
    }
    val targetLeftPupilY = when (hudState) {
        LiveVoiceHudState.THINKING -> 0f
        LiveVoiceHudState.SLOW_NETWORK -> -0.15f
        else -> 0f
    }

    // ── Right Eye Geometries ──
    val targetRightWidth = when (hudState) {
        LiveVoiceHudState.LISTENING -> 58f + breatheAmplitude * 0.4f
        LiveVoiceHudState.THINKING -> 58f + (breathingOscillator * 2f)
        LiveVoiceHudState.PROCESSING -> 106f
        LiveVoiceHudState.SPEAKING -> 58f - (effectiveAudioEnergy * 4f)
        LiveVoiceHudState.SLOW_NETWORK -> 62f
        LiveVoiceHudState.RECONNECTING -> 90f
        LiveVoiceHudState.DISCONNECTING -> 60f
    }
    val targetRightHeight = when (hudState) {
        LiveVoiceHudState.LISTENING -> 84f + breatheAmplitude
        LiveVoiceHudState.THINKING -> 82f + (breathingOscillator * 3f)
        LiveVoiceHudState.PROCESSING -> 26f
        LiveVoiceHudState.SPEAKING -> 76f + (effectiveAudioEnergy * 32f)
        LiveVoiceHudState.SLOW_NETWORK -> 48f
        LiveVoiceHudState.RECONNECTING -> 22f
        LiveVoiceHudState.DISCONNECTING -> 6f // Gentle eye close
    }
    val targetRightCornerRadius = when (hudState) {
        LiveVoiceHudState.LISTENING -> 29f
        LiveVoiceHudState.THINKING -> 29f // Elegant symmetrical stadium capsule
        LiveVoiceHudState.PROCESSING -> 13f
        LiveVoiceHudState.SPEAKING -> 29f
        LiveVoiceHudState.SLOW_NETWORK -> 24f
        LiveVoiceHudState.RECONNECTING -> 11f
        LiveVoiceHudState.DISCONNECTING -> 3f
    }
    val targetRightRotation = when (hudState) {
        LiveVoiceHudState.LISTENING -> 0f
        LiveVoiceHudState.THINKING -> 0f
        LiveVoiceHudState.PROCESSING -> 0f
        LiveVoiceHudState.SPEAKING -> 0f
        LiveVoiceHudState.SLOW_NETWORK -> 8f
        LiveVoiceHudState.RECONNECTING -> 0f
        LiveVoiceHudState.DISCONNECTING -> 0f
    }
    val targetRightOffsetY = when (hudState) {
        LiveVoiceHudState.LISTENING -> 0f
        LiveVoiceHudState.THINKING -> -4f
        LiveVoiceHudState.PROCESSING -> 6f
        LiveVoiceHudState.SPEAKING -> -(effectiveAudioEnergy * 3f) // Subtle vertical bounce
        LiveVoiceHudState.SLOW_NETWORK -> 2f
        LiveVoiceHudState.RECONNECTING -> 6f
        LiveVoiceHudState.DISCONNECTING -> 4f
    }
    val targetRightPupilRadius = when (hudState) {
        LiveVoiceHudState.THINKING -> 0f // Pure solid OLED glowing capsule
        LiveVoiceHudState.SLOW_NETWORK -> 11f
        else -> 0f
    }
    val targetRightPupilX = when (hudState) {
        LiveVoiceHudState.THINKING -> 0f
        else -> 0f
    }
    val targetRightPupilY = when (hudState) {
        LiveVoiceHudState.THINKING -> 0f
        LiveVoiceHudState.SLOW_NETWORK -> -0.15f
        else -> 0f
    }

    // ── Eye Colors ──
    val targetCoreColor = when (hudState) {
        LiveVoiceHudState.LISTENING -> Color(0xFF64FFDA) // Cyan
        LiveVoiceHudState.THINKING -> Color(0xFF10B981) // Emerald Green
        LiveVoiceHudState.PROCESSING -> Color(0xFFF59E0B) // Amber Gold
        LiveVoiceHudState.SPEAKING -> Color(0xFF38BDF8) // Electric Blue
        LiveVoiceHudState.SLOW_NETWORK -> Color(0xFFFB923C) // Orange Coral
        LiveVoiceHudState.RECONNECTING -> Color(0xFFEF4444) // Crimson Red
        LiveVoiceHudState.DISCONNECTING -> Color(0xFF94A3B8) // Slate Gray
    }

    val targetGlowColor = when (hudState) {
        LiveVoiceHudState.LISTENING -> Color(0xFF64FFDA).copy(alpha = 0.40f)
        LiveVoiceHudState.THINKING -> Color(0xFF10B981).copy(alpha = 0.45f)
        LiveVoiceHudState.PROCESSING -> Color(0xFFF59E0B).copy(alpha = 0.45f)
        LiveVoiceHudState.SPEAKING -> Color(0xFF38BDF8).copy(alpha = 0.50f)
        LiveVoiceHudState.SLOW_NETWORK -> Color(0xFFFB923C).copy(alpha = 0.40f)
        LiveVoiceHudState.RECONNECTING -> Color(0xFFEF4444).copy(alpha = 0.55f)
        LiveVoiceHudState.DISCONNECTING -> Color(0xFF94A3B8).copy(alpha = 0.20f)
    }

    // ── Fluid Spring Animated Values ──
    // For SPEAKING state: use fast tween so eyes track audio in real-time
    // For other states: use spring for organic elastic transitions
    val speakingTween = tween<Float>(60, easing = FastOutSlowInEasing)
    val isSpeakingState = hudState == LiveVoiceHudState.SPEAKING

    val leftWidth by animateFloatAsState(
        targetLeftWidth,
        if (isSpeakingState) speakingTween else springSpec,
        label = "LW"
    )
    val leftHeight by animateFloatAsState(
        targetLeftHeight,
        if (isSpeakingState) speakingTween else springSpec,
        label = "LH"
    )
    val leftCornerRadius by animateFloatAsState(targetLeftCornerRadius, springSpec, label = "LCR")
    val leftRotation by animateFloatAsState(targetLeftRotation, springSpec, label = "LR")
    val leftOffsetY by animateFloatAsState(
        targetLeftOffsetY,
        if (isSpeakingState) speakingTween else springSpec,
        label = "LOY"
    )
    val leftPupilRadius by animateFloatAsState(targetLeftPupilRadius, springSpec, label = "LPR")
    val leftPupilX by animateFloatAsState(
        targetLeftPupilX,
        if (hudState == LiveVoiceHudState.THINKING) tween(900) else springSpec,
        label = "LPX"
    )
    val leftPupilY by animateFloatAsState(
        targetLeftPupilY,
        if (hudState == LiveVoiceHudState.THINKING) tween(900) else springSpec,
        label = "LPY"
    )

    val rightWidth by animateFloatAsState(
        targetRightWidth,
        if (isSpeakingState) speakingTween else springSpec,
        label = "RW"
    )
    val rightHeight by animateFloatAsState(
        targetRightHeight,
        if (isSpeakingState) speakingTween else springSpec,
        label = "RH"
    )
    val rightCornerRadius by animateFloatAsState(targetRightCornerRadius, springSpec, label = "RCR")
    val rightRotation by animateFloatAsState(targetRightRotation, springSpec, label = "RR")
    val rightOffsetY by animateFloatAsState(
        targetRightOffsetY,
        if (isSpeakingState) speakingTween else springSpec,
        label = "ROY"
    )
    val rightPupilRadius by animateFloatAsState(targetRightPupilRadius, springSpec, label = "RPR")
    val rightPupilX by animateFloatAsState(
        targetRightPupilX,
        if (hudState == LiveVoiceHudState.THINKING) tween(900) else springSpec,
        label = "RPX"
    )
    val rightPupilY by animateFloatAsState(
        targetRightPupilY,
        if (hudState == LiveVoiceHudState.THINKING) tween(900) else springSpec,
        label = "RPY"
    )

    val coreColor by animateColorAsState(
        targetCoreColor,
        tween(450, easing = FastOutSlowInEasing),
        label = "CoreColor"
    )
    val glowColor by animateColorAsState(
        targetGlowColor,
        tween(450, easing = FastOutSlowInEasing),
        label = "GlowColor"
    )

    // Outer audio ripple alpha (only visible during SPEAKING)
    val outerRippleAlpha by animateFloatAsState(
        if (hudState == LiveVoiceHudState.SPEAKING) 0.35f + (smoothedAudioEnergy * 0.65f) else 0f,
        tween(120),
        label = "RippleAlpha"
    )

    // Reconnecting warning blink effect
    val eyeAlpha = if (hudState == LiveVoiceHudState.RECONNECTING) warningPulse else 1f

    // ─── 5. Drawing Canvas ────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val isLeft = offset.x < size.width / 2f
                        val target = if (isLeft) "LEFT" else "RIGHT"
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        coroutineScope.launch {
                            launch {
                                leftTapBlink.animateTo(0.05f, tween(50, easing = FastOutSlowInEasing))
                                leftTapBlink.animateTo(1.15f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
                                leftTapBlink.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh))
                            }
                            launch {
                                rightTapBlink.animateTo(0.05f, tween(50, easing = FastOutSlowInEasing))
                                rightTapBlink.animateTo(1.15f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
                                rightTapBlink.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh))
                            }
                        }
                        onEyeTapped(target, 2)
                    },
                    onTap = { offset ->
                        val isLeft = offset.x < size.width / 2f
                        val target = if (isLeft) "LEFT" else "RIGHT"
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        coroutineScope.launch {
                            if (isLeft) {
                                leftTapBlink.animateTo(0.05f, tween(60, easing = FastOutSlowInEasing))
                                leftTapBlink.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
                            } else {
                                rightTapBlink.animateTo(0.05f, tween(60, easing = FastOutSlowInEasing))
                                rightTapBlink.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                        onEyeTapped(target, 1)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .graphicsLayer { alpha = eyeAlpha }
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            // Eye center spacing
            val eyeSpacing = 72.dp.toPx()
            val saccadeOffset = animatedSaccadeX.dp.toPx()
            val leftEyeCenterX = centerX - eyeSpacing + saccadeOffset
            val rightEyeCenterX = centerX + eyeSpacing + saccadeOffset

            // ── Draw Outer Audio Ripples (when speaking) ──
            if (outerRippleAlpha > 0.01f) {
                drawOuterAudioRipples(
                    leftCenterX = leftEyeCenterX,
                    rightCenterX = rightEyeCenterX,
                    centerY = centerY,
                    color = coreColor,
                    alpha = outerRippleAlpha,
                    audioEnergy = smoothedAudioEnergy
                )
            }

            // ── Draw Left Eye ──
            drawSingleEye(
                centerX = leftEyeCenterX,
                centerY = centerY + leftOffsetY.dp.toPx(),
                width = leftWidth.dp.toPx(),
                height = leftHeight.dp.toPx(),
                cornerRadius = leftCornerRadius.dp.toPx(),
                rotationDegrees = leftRotation,
                pupilRadius = leftPupilRadius.dp.toPx(),
                pupilNormX = leftPupilX,
                pupilNormY = leftPupilY,
                coreColor = coreColor,
                glowColor = glowColor,
                blinkScaleY = blinkAnimatable.value * leftTapBlink.value,
                isVisor = (hudState == LiveVoiceHudState.PROCESSING || hudState == LiveVoiceHudState.RECONNECTING),
                scanShimmer = scanShimmer
            )

            // ── Draw Right Eye ──
            drawSingleEye(
                centerX = rightEyeCenterX,
                centerY = centerY + rightOffsetY.dp.toPx(),
                width = rightWidth.dp.toPx(),
                height = rightHeight.dp.toPx(),
                cornerRadius = rightCornerRadius.dp.toPx(),
                rotationDegrees = rightRotation,
                pupilRadius = rightPupilRadius.dp.toPx(),
                pupilNormX = rightPupilX,
                pupilNormY = rightPupilY,
                coreColor = coreColor,
                glowColor = glowColor,
                blinkScaleY = blinkAnimatable.value * rightTapBlink.value,
                isVisor = (hudState == LiveVoiceHudState.PROCESSING || hudState == LiveVoiceHudState.RECONNECTING),
                scanShimmer = scanShimmer
            )
        }
    }
}

/**
 * Renders a single cybernetic OLED eye with glow bloom, pupil cutout, and visor scanlines.
 */
private fun DrawScope.drawSingleEye(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
    rotationDegrees: Float,
    pupilRadius: Float,
    pupilNormX: Float,
    pupilNormY: Float,
    coreColor: Color,
    glowColor: Color,
    blinkScaleY: Float,
    isVisor: Boolean,
    scanShimmer: Float
) {
    // Guard: skip drawing if size is negligible (prevents NaN/artifact issues)
    if (width < 1f || height < 1f) return

    rotate(degrees = rotationDegrees, pivot = Offset(centerX, centerY)) {
        scale(scaleX = 1f, scaleY = blinkScaleY, pivot = Offset(centerX, centerY)) {

            val effectiveHeight = height * blinkScaleY
            val left = centerX - (width / 2f)
            val top = centerY - (effectiveHeight / 2f)
            val rectSize = Size(width, effectiveHeight)
            val safeCornerRadius = CornerRadius(
                cornerRadius.coerceAtMost(effectiveHeight / 2f).coerceAtMost(width / 2f)
            )

            // 1. Layer 1: Outer Neon Bloom Glow (1.35x enlarged, soft alpha)
            val glowPaddingX = width * 0.16f
            val glowPaddingY = effectiveHeight * 0.16f
            val glowCorner = CornerRadius(
                (cornerRadius + glowPaddingX)
                    .coerceAtMost((effectiveHeight + glowPaddingY * 2) / 2f)
                    .coerceAtMost((width + glowPaddingX * 2) / 2f)
            )
            drawRoundRect(
                color = glowColor.copy(alpha = (glowColor.alpha * 0.7f).coerceIn(0f, 1f)),
                topLeft = Offset(left - glowPaddingX, top - glowPaddingY),
                size = Size(width + glowPaddingX * 2, effectiveHeight + glowPaddingY * 2),
                cornerRadius = glowCorner
            )

            // 2. Layer 2: Core Neon Solid Eye Shape
            drawRoundRect(
                color = coreColor,
                topLeft = Offset(left, top),
                size = rectSize,
                cornerRadius = safeCornerRadius
            )

            // 3. Layer 3: Pupil Cutout (Dark true-AMOLED iris inside the glowing eye)
            if (pupilRadius > 1.5f && effectiveHeight > pupilRadius * 2) {
                val maxPupilOffsetX = ((width / 2f) - pupilRadius - 4f).coerceAtLeast(0f)
                val maxPupilOffsetY = ((effectiveHeight / 2f) - pupilRadius - 4f).coerceAtLeast(0f)
                val pupilCenterX = centerX + (pupilNormX * maxPupilOffsetX)
                val pupilCenterY = centerY + (pupilNormY * maxPupilOffsetY)

                // Draw pitch-black AMOLED cutout
                drawCircle(
                    color = Color(0xFF06090E),
                    radius = pupilRadius,
                    center = Offset(pupilCenterX, pupilCenterY)
                )

                // Subtle inner glow ring around pupil
                drawCircle(
                    color = coreColor.copy(alpha = 0.5f),
                    radius = pupilRadius,
                    center = Offset(pupilCenterX, pupilCenterY),
                    style = Stroke(width = 1.5f.dp.toPx())
                )
            }

            // 4. Layer 4: Visor Internal Scanning Line (Active during Processing/Reconnecting)
            // Use dp-based threshold to ensure scanline only appears on wide visor shapes
            if (isVisor && width > 60.dp.toPx() * 0.5f) {
                val shimmerX = centerX + (scanShimmer * (width * 0.42f))
                val scanAlpha = (1f - abs(scanShimmer) * 0.3f).coerceIn(0.4f, 0.8f)
                drawLine(
                    color = Color.White.copy(alpha = scanAlpha),
                    start = Offset(shimmerX, top + 3f),
                    end = Offset(shimmerX, top + effectiveHeight - 3f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Draws dynamic audio soundwave ripples on the outer flanks of the eyes when speaking.
 */
private fun DrawScope.drawOuterAudioRipples(
    leftCenterX: Float,
    rightCenterX: Float,
    centerY: Float,
    color: Color,
    alpha: Float,
    audioEnergy: Float
) {
    val clampedEnergy = audioEnergy.coerceIn(0f, 1f)
    val strokeWidth = (2.5f + clampedEnergy * 1.5f).dp.toPx()
    val arcHeight = (46f + clampedEnergy * 24f).dp.toPx()

    // Outer Left Waves
    val leftOffset1 = leftCenterX - 48.dp.toPx() - (clampedEnergy * 8.dp.toPx())
    val leftOffset2 = leftCenterX - 62.dp.toPx() - (clampedEnergy * 14.dp.toPx())
    val arcWidth = 20.dp.toPx()

    drawArc(
        color = color.copy(alpha = (alpha * 0.85f).coerceIn(0f, 1f)),
        startAngle = 120f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(leftOffset1 - arcWidth / 2, centerY - (arcHeight / 2f)),
        size = Size(arcWidth, arcHeight),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )

    drawArc(
        color = color.copy(alpha = (alpha * 0.45f).coerceIn(0f, 1f)),
        startAngle = 120f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(leftOffset2 - arcWidth / 2, centerY - (arcHeight * 0.8f / 2f)),
        size = Size(arcWidth, arcHeight * 0.8f),
        style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round)
    )

    // Outer Right Waves
    val rightOffset1 = rightCenterX + 48.dp.toPx() + (clampedEnergy * 8.dp.toPx())
    val rightOffset2 = rightCenterX + 62.dp.toPx() + (clampedEnergy * 14.dp.toPx())

    drawArc(
        color = color.copy(alpha = (alpha * 0.85f).coerceIn(0f, 1f)),
        startAngle = -60f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(rightOffset1 - arcWidth / 2, centerY - (arcHeight / 2f)),
        size = Size(arcWidth, arcHeight),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )

    drawArc(
        color = color.copy(alpha = (alpha * 0.45f).coerceIn(0f, 1f)),
        startAngle = -60f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(rightOffset2 - arcWidth / 2, centerY - (arcHeight * 0.8f / 2f)),
        size = Size(arcWidth, arcHeight * 0.8f),
        style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round)
    )
}
