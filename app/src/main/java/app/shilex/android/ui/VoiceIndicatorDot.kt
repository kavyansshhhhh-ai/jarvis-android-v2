package app.shilex.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.shilex.android.wake.VoiceIndicatorSnapshot
import app.shilex.android.wake.VoiceIndicatorState

/**
 * Animated breathing and audio-reactive JARVIS Voice Indicator Dot (Part 7).
 *
 * States:
 * - OFF / IDLE_TIMEOUT: Hidden completely (zero alpha, zero size).
 * - LISTENING / INTERRUPTED: Compact dot with gentle, natural breathing animation.
 * - PROCESSING: Subtle glowing pulse.
 * - SPEAKING: Fluid natural breathing expanding and contracting, driven by live audio amplitude
 *   when available or smooth sinusoidal breathing fallback.
 */
@Composable
fun AnimatedVoiceIndicatorDot(
    snapshot: VoiceIndicatorSnapshot,
    modifier: Modifier = Modifier,
) {
    val isVisible = snapshot.visible &&
        snapshot.state != VoiceIndicatorState.OFF &&
        snapshot.state != VoiceIndicatorState.IDLE_TIMEOUT

    val targetAlpha = if (isVisible) 1f else 0f
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 200),
        label = "voiceDotAlpha",
    )

    if (animatedAlpha <= 0.005f) return

    val infinite = rememberInfiniteTransition(label = "voiceDotBreathing")

    // Gentle breathing pulse for listening mode (1.6s cycle)
    val listeningBreath by infinite.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "listeningBreath",
    )

    // Smooth speaking breathing fallback (0.9s cycle)
    val speakingBreathFallback by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakingBreathFallback",
    )

    // Live speech amplitude smoothing
    val smoothAmplitude by animateFloatAsState(
        targetValue = snapshot.amplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 600f),
        label = "smoothAmplitude",
    )

    val scale = when (snapshot.state) {
        VoiceIndicatorState.OFF, VoiceIndicatorState.IDLE_TIMEOUT -> 0f
        VoiceIndicatorState.LISTENING, VoiceIndicatorState.INTERRUPTED -> listeningBreath
        VoiceIndicatorState.PROCESSING -> 1.05f
        VoiceIndicatorState.SPEAKING -> {
            if (smoothAmplitude > 0.02f) {
                1.0f + smoothAmplitude * 0.75f
            } else {
                speakingBreathFallback
            }
        }
    }

    val glowAlphaMultiplier = when (snapshot.state) {
        VoiceIndicatorState.SPEAKING -> 0.85f + (smoothAmplitude * 0.40f)
        VoiceIndicatorState.PROCESSING -> 0.70f
        VoiceIndicatorState.LISTENING, VoiceIndicatorState.INTERRUPTED -> 0.55f
        else -> 0f
    }
    val cyanAccent = ElectricCyan
    val violetAccent = NeonViolet

    Canvas(modifier = modifier.size(24.dp)) {
        val c = center
        val baseRadius = 5.dp.toPx()
        val coreRadius = baseRadius * scale
        val glowRadius = coreRadius * 2.2f

        // Outer cyan/violet ambient glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    cyanAccent.copy(alpha = 0.65f * animatedAlpha * glowAlphaMultiplier),
                    violetAccent.copy(alpha = 0.35f * animatedAlpha * glowAlphaMultiplier),
                    Color.Transparent,
                ),
                center = c,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = c,
        )

        // Specular inner luminous core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.98f * animatedAlpha),
                    cyanAccent.copy(alpha = 0.95f * animatedAlpha),
                    violetAccent.copy(alpha = 0.80f * animatedAlpha),
                ),
                center = Offset(c.x - coreRadius * 0.22f, c.y - coreRadius * 0.22f),
                radius = coreRadius * 1.25f,
            ),
            radius = coreRadius,
            center = c,
        )
    }
}
