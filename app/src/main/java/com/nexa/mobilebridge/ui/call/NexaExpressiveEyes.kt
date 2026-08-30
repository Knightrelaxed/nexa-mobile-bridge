package com.nexa.mobilebridge.ui.call

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * NexaExpressiveEyes
 *
 * Vector-rendered Cybernetic OLED Robot Eyes inspired by EMO & Vector robots.
 * Fully continuous fluid morphing across all 6 Call States in Jetpack Compose Canvas.
 */
@Composable
fun NexaExpressiveEyes(
    hudState: LiveVoiceHudState,
    isSpeaking: Boolean,
    audioEnergy: Float = 0f,
    modifier: Modifier = Modifier
) {
    // ─── 1. Autonomous Decoupled Blink Physics Engine ─────────────────────────────
    var blinkScaleY by remember { mutableFloatStateOf(1f) }
    var eyeSaccadeX by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(hudState) {
        while (true) {
            val nextBlinkInterval = Random.nextLong(3200, 5200)
            delay(nextBlinkInterval)

            // Only blink during listening, thinking, or slow network
            if (hudState == LiveVoiceHudState.LISTENING ||
                hudState == LiveVoiceHudState.THINKING ||
                hudState == LiveVoiceHudState.SLOW_NETWORK
            ) {
                // Natural Saccade Micro-Shift before blinking
                eyeSaccadeX = Random.nextDouble(-3.0, 3.0).toFloat()

                // Fast Blink Down (70ms)
                val blinkStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - blinkStart < 70) {
                    val progress = (System.currentTimeMillis() - blinkStart) / 70f
                    blinkScaleY = (1f - (progress * 0.94f)).coerceIn(0.06f, 1f)
                    delay(16)
                }
                blinkScaleY = 0.06f
                delay(20)

                // Springy Blink Up (90ms)
                val reopenStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - reopenStart < 90) {
                    val progress = (System.currentTimeMillis() - reopenStart) / 90f
                    blinkScaleY = (0.06f + (progress * 0.94f)).coerceIn(0.06f, 1f)
                    delay(16)
                }
                blinkScaleY = 1f
            }
        }
    }

    // ─── 2. Continuous Organic Oscillators ─────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "EyeOscillators")

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

    // ─── 3. State-Driven Fluid Spring Physics Transitions ─────────────────────────
    val springSpec = spring<Float>(
        dampingRatio = 0.65f, // Smooth low-overshoot bounce
        stiffness = Spring.StiffnessMediumLow
    )

    // Effective speech bounce energy combining RMS and synthetic syllable bounce
    val effectiveAudioEnergy = if (hudState == LiveVoiceHudState.SPEAKING) {
        (audioEnergy.coerceIn(0f, 1f) * 0.7f + talkOscillator * 0.3f)
    } else 0f

    // ── Left Eye Geometries ──
    val targetLeftWidth = when (hudState) {
        LiveVoiceHudState.LISTENING -> 58f
        LiveVoiceHudState.THINKING -> 74f
        LiveVoiceHudState.PROCESSING -> 106f
        LiveVoiceHudState.SPEAKING -> 58f - (effectiveAudioEnergy * 4f)
        LiveVoiceHudState.SLOW_NETWORK -> 64f
        LiveVoiceHudState.RECONNECTING -> 90f
    }
    val targetLeftHeight = when (hudState) {
        LiveVoiceHudState.LISTENING -> 84f
        LiveVoiceHudState.THINKING -> 74f
        LiveVoiceHudState.PROCESSING -> 26f
        LiveVoiceHudState.SPEAKING -> 76f + (effectiveAudioEnergy * 32f)
        LiveVoiceHudState.SLOW_NETWORK -> 44f
        LiveVoiceHudState.RECONNECTING -> 22f
    }
    val targetLeftCornerRadius = when (hudState) {
        LiveVoiceHudState.LISTENING -> 29f
        LiveVoiceHudState.THINKING -> 37f // Round
        LiveVoiceHudState.PROCESSING -> 13f // Visor
        LiveVoiceHudState.SPEAKING -> 29f
        LiveVoiceHudState.SLOW_NETWORK -> 22f
        LiveVoiceHudState.RECONNECTING -> 11f
    }
    val targetLeftRotation = when (hudState) {
        LiveVoiceHudState.LISTENING -> 0f
        LiveVoiceHudState.THINKING -> -6f // Thinking tilt
        LiveVoiceHudState.PROCESSING -> 0f
        LiveVoiceHudState.SPEAKING -> 0f
        LiveVoiceHudState.SLOW_NETWORK -> -10f // Droop puzzled
        LiveVoiceHudState.RECONNECTING -> 0f
    }
    val targetLeftOffsetY = when (hudState) {
        LiveVoiceHudState.LISTENING -> 0f
        LiveVoiceHudState.THINKING -> -6f
        LiveVoiceHudState.PROCESSING -> 6f
        LiveVoiceHudState.SPEAKING -> 0f
        LiveVoiceHudState.SLOW_NETWORK -> 2f
        LiveVoiceHudState.RECONNECTING -> 6f
    }
    val targetLeftPupilRadius = when (hudState) {
        LiveVoiceHudState.THINKING -> 22f
        LiveVoiceHudState.SLOW_NETWORK -> 11f
        else -> 0f
    }
    val targetLeftPupilX = when (hudState) {
        LiveVoiceHudState.THINKING -> 0.44f // Looking up-right
        else -> 0f
    }
    val targetLeftPupilY = when (hudState) {
        LiveVoiceHudState.THINKING -> -0.44f // Looking up-right
        LiveVoiceHudState.SLOW_NETWORK -> -0.15f
        else -> 0f
    }

    // ── Right Eye Geometries ──
    val targetRightWidth = when (hudState) {
        LiveVoiceHudState.LISTENING -> 58f
        LiveVoiceHudState.THINKING -> 68f
        LiveVoiceHudState.PROCESSING -> 106f
        LiveVoiceHudState.SPEAKING -> 58f - (effectiveAudioEnergy * 4f)
        LiveVoiceHudState.SLOW_NETWORK -> 62f
        LiveVoiceHudState.RECONNECTING -> 90f
    }
    val targetRightHeight = when (hudState) {
        LiveVoiceHudState.LISTENING -> 84f
        LiveVoiceHudState.THINKING -> 44f // Squinted thinking
        LiveVoiceHudState.PROCESSING -> 26f
        LiveVoiceHudState.SPEAKING -> 76f + (effectiveAudioEnergy * 32f)
        LiveVoiceHudState.SLOW_NETWORK -> 48f
        LiveVoiceHudState.RECONNECTING -> 22f
    }
    val targetRightCornerRadius = when (hudState) {
        LiveVoiceHudState.LISTENING -> 29f
        LiveVoiceHudState.THINKING -> 22f
        LiveVoiceHudState.PROCESSING -> 13f
        LiveVoiceHudState.SPEAKING -> 29f
        LiveVoiceHudState.SLOW_NETWORK -> 24f
        LiveVoiceHudState.RECONNECTING -> 11f
    }
    val targetRightRotation = when (hudState) {
        LiveVoiceHudState.LISTENING -> 0f
        LiveVoiceHudState.THINKING -> 5f
        LiveVoiceHudState.PROCESSING -> 0f
        LiveVoiceHudState.SPEAKING -> 0f
        LiveVoiceHudState.SLOW_NETWORK -> 8f
        LiveVoiceHudState.RECONNECTING -> 0f
    }
    val targetRightOffsetY = when (hudState) {
        LiveVoiceHudState.LISTENING -> 0f
        LiveVoiceHudState.THINKING -> -4f
        LiveVoiceHudState.PROCESSING -> 6f
        LiveVoiceHudState.SPEAKING -> 0f
        LiveVoiceHudState.SLOW_NETWORK -> 2f
        LiveVoiceHudState.RECONNECTING -> 6f
    }
    val targetRightPupilRadius = when (hudState) {
        LiveVoiceHudState.THINKING -> 16f
        LiveVoiceHudState.SLOW_NETWORK -> 11f
        else -> 0f
    }
    val targetRightPupilX = when (hudState) {
        LiveVoiceHudState.THINKING -> 0.38f
        else -> 0f
    }
    val targetRightPupilY = when (hudState) {
        LiveVoiceHudState.THINKING -> -0.35f
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
    }

    val targetGlowColor = when (hudState) {
        LiveVoiceHudState.LISTENING -> Color(0xFF64FFDA).copy(alpha = 0.40f)
        LiveVoiceHudState.THINKING -> Color(0xFF10B981).copy(alpha = 0.45f)
        LiveVoiceHudState.PROCESSING -> Color(0xFFF59E0B).copy(alpha = 0.45f)
        LiveVoiceHudState.SPEAKING -> Color(0xFF38BDF8).copy(alpha = 0.50f)
        LiveVoiceHudState.SLOW_NETWORK -> Color(0xFFFB923C).copy(alpha = 0.40f)
        LiveVoiceHudState.RECONNECTING -> Color(0xFFEF4444).copy(alpha = 0.55f)
    }

    // ── Fluid Spring Animated Values ──
    val leftWidth by animateFloatAsState(targetLeftWidth, springSpec, label = "LW")
    val leftHeight by animateFloatAsState(targetLeftHeight, springSpec, label = "LH")
    val leftCornerRadius by animateFloatAsState(targetLeftCornerRadius, springSpec, label = "LCR")
    val leftRotation by animateFloatAsState(targetLeftRotation, springSpec, label = "LR")
    val leftOffsetY by animateFloatAsState(targetLeftOffsetY, springSpec, label = "LOY")
    val leftPupilRadius by animateFloatAsState(targetLeftPupilRadius, springSpec, label = "LPR")
    val leftPupilX by animateFloatAsState(targetLeftPupilX, springSpec, label = "LPX")
    val leftPupilY by animateFloatAsState(targetLeftPupilY, springSpec, label = "LPY")

    val rightWidth by animateFloatAsState(targetRightWidth, springSpec, label = "RW")
    val rightHeight by animateFloatAsState(targetRightHeight, springSpec, label = "RH")
    val rightCornerRadius by animateFloatAsState(targetRightCornerRadius, springSpec, label = "RCR")
    val rightRotation by animateFloatAsState(targetRightRotation, springSpec, label = "RR")
    val rightOffsetY by animateFloatAsState(targetRightOffsetY, springSpec, label = "ROY")
    val rightPupilRadius by animateFloatAsState(targetRightPupilRadius, springSpec, label = "RPR")
    val rightPupilX by animateFloatAsState(targetRightPupilX, springSpec, label = "RPX")
    val rightPupilY by animateFloatAsState(targetRightPupilY, springSpec, label = "RPY")

    val coreColor by animateColorAsState(targetCoreColor, tween(320, easing = FastOutSlowInEasing), label = "CoreColor")
    val glowColor by animateColorAsState(targetGlowColor, tween(320, easing = FastOutSlowInEasing), label = "GlowColor")

    val outerRippleAlpha by animateFloatAsState(
        if (hudState == LiveVoiceHudState.SPEAKING) 0.35f + (effectiveAudioEnergy * 0.65f) else 0f,
        springSpec,
        label = "RippleAlpha"
    )

    val eyeAlpha = if (hudState == LiveVoiceHudState.RECONNECTING) warningPulse else 1f

    // ─── 4. Drawing Canvas ────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
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
            val leftEyeCenterX = centerX - eyeSpacing + (eyeSaccadeX.dp.toPx())
            val rightEyeCenterX = centerX + eyeSpacing + (eyeSaccadeX.dp.toPx())

            // ── Draw Outer Audio Ripples (when speaking) ──
            if (outerRippleAlpha > 0.05f) {
                drawOuterAudioRipples(
                    leftCenterX = leftEyeCenterX,
                    rightCenterX = rightEyeCenterX,
                    centerY = centerY,
                    color = coreColor,
                    alpha = outerRippleAlpha,
                    audioEnergy = effectiveAudioEnergy
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
                blinkScaleY = blinkScaleY,
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
                blinkScaleY = blinkScaleY,
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
    rotate(degrees = rotationDegrees, pivot = Offset(centerX, centerY)) {
        scale(scaleX = 1f, scaleY = blinkScaleY, pivot = Offset(centerX, centerY)) {

            val left = centerX - (width / 2f)
            val top = centerY - (height / 2f)
            val rectSize = Size(width, height)
            val safeCornerRadius = CornerRadius(cornerRadius.coerceAtMost(height / 2f))

            // 1. Layer 1: Outer Neon Bloom Glow (1.35x enlarged, soft alpha)
            val glowPaddingX = width * 0.16f
            val glowPaddingY = height * 0.16f
            val glowRect = RoundRect(
                left = left - glowPaddingX,
                top = top - glowPaddingY,
                right = left + width + glowPaddingX,
                bottom = top + height + glowPaddingY,
                cornerRadius = CornerRadius((cornerRadius + glowPaddingX).coerceAtMost((height + glowPaddingY * 2) / 2f))
            )
            drawRoundRect(
                color = glowColor.copy(alpha = 0.28f),
                topLeft = Offset(glowRect.left, glowRect.top),
                size = Size(glowRect.width, glowRect.height),
                cornerRadius = glowRect.topLeftCornerRadius
            )

            // 2. Layer 2: Core Neon Solid Eye Shape
            drawRoundRect(
                color = coreColor,
                topLeft = Offset(left, top),
                size = rectSize,
                cornerRadius = safeCornerRadius
            )

            // 3. Layer 3: Pupil Cutout (Dark true-AMOLED iris inside the glowing eye)
            if (pupilRadius > 1.5f) {
                val maxPupilOffsetX = (width / 2f) - pupilRadius - 4f
                val maxPupilOffsetY = (height / 2f) - pupilRadius - 4f
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

            // 4. Layer 4: Visor Internal Scanning Line (Active during Processing)
            if (isVisor && width > 60f) {
                val shimmerX = centerX + (scanShimmer * (width / 2f))
                drawLine(
                    color = Color.White.copy(alpha = 0.7f),
                    start = Offset(shimmerX, top + 3f),
                    end = Offset(shimmerX, top + height - 3f),
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
    val strokeWidth = (2.5f + audioEnergy * 1.5f).dp.toPx()
    val arcHeight = (46f + audioEnergy * 24f).dp.toPx()

    // Outer Left Waves
    val leftOffset1 = leftCenterX - 48.dp.toPx() - (audioEnergy * 8.dp.toPx())
    val leftOffset2 = leftCenterX - 62.dp.toPx() - (audioEnergy * 14.dp.toPx())

    drawArc(
        color = color.copy(alpha = alpha * 0.85f),
        startAngle = 120f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(leftOffset1 - 10.dp.toPx(), centerY - (arcHeight / 2f)),
        size = Size(20.dp.toPx(), arcHeight),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )

    drawArc(
        color = color.copy(alpha = alpha * 0.45f),
        startAngle = 120f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(leftOffset2 - 10.dp.toPx(), centerY - (arcHeight * 0.8f / 2f)),
        size = Size(20.dp.toPx(), arcHeight * 0.8f),
        style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round)
    )

    // Outer Right Waves
    val rightOffset1 = rightCenterX + 48.dp.toPx() + (audioEnergy * 8.dp.toPx())
    val rightOffset2 = rightCenterX + 62.dp.toPx() + (audioEnergy * 14.dp.toPx())

    drawArc(
        color = color.copy(alpha = alpha * 0.85f),
        startAngle = -60f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(rightOffset1 - 10.dp.toPx(), centerY - (arcHeight / 2f)),
        size = Size(20.dp.toPx(), arcHeight),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )

    drawArc(
        color = color.copy(alpha = alpha * 0.45f),
        startAngle = -60f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(rightOffset2 - 10.dp.toPx(), centerY - (arcHeight * 0.8f / 2f)),
        size = Size(20.dp.toPx(), arcHeight * 0.8f),
        style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round)
    )
}
