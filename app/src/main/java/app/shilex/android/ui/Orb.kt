package app.shilex.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import app.shilex.android.assistant.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val LiquidViolet = Color(0xFF9D65FF)
private val LiquidBlue = Color(0xFF388BFF)
private val LiquidPink = Color(0xFFF472B6)
private val LiquidDeep = Color(0xFF0F1B42)
private val LiquidCyan = Color(0xFF38E5FF)

/**
 * Premium iOS-inspired Liquid Glass AI Presence Orb.
 * Breathing liquid glass presence with specular highlights, multi-chromatic inner liquid drift,
 * and voice-level responsiveness (0..1).
 */
@Composable
fun Orb(phase: Phase, modifier: Modifier = Modifier, level: Float = 0f) {
    val accent = ElectricCyan
    val highlight = 0.65f // High specular glass reflection on OLED black

    val energyState = animateFloatAsState(
        when (phase) {
            Phase.Off -> 0.05f
            Phase.Connecting, Phase.Processing -> 0.35f
            Phase.Listening -> 0.55f
            Phase.Speaking -> 0.95f
        },
        tween(600),
        label = "energy",
    )

    val voiceState = animateFloatAsState(level, spring(stiffness = Spring.StiffnessLow), label = "voice")

    val tState = if (phase == Phase.Off) {
        null
    } else {
        rememberInfiniteTransition(label = "orb").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "t",
        )
    }

    val lights = remember(accent) {
        listOf(
            listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0f)),
            listOf(LiquidViolet.copy(alpha = 0.85f), LiquidViolet.copy(alpha = 0f)),
            listOf(LiquidPink.copy(alpha = 0.85f), LiquidPink.copy(alpha = 0f)),
        )
    }
    val rimColors = remember(accent) {
        listOf(
            Color.White.copy(alpha = 0.6f),
            accent.copy(alpha = 0.8f),
            LiquidViolet.copy(alpha = 0.4f),
            Color.White.copy(alpha = 0.7f),
        )
    }
    val ring1Colors = remember(accent) { listOf(Color.Transparent, accent, Color.Transparent) }
    val ring2Colors = remember { listOf(Color.Transparent, LiquidViolet, Color.Transparent) }
    val substrateColors = remember { listOf(LiquidBlue, LiquidDeep) }
    val specularColors = remember { listOf(Color.White.copy(alpha = highlight), Color.Transparent) }
    val causticColors = remember { listOf(Color.Transparent, LiquidCyan.copy(alpha = 0.35f)) }

    Spacer(
        modifier = modifier.drawWithCache {
            val path = Path()
            val c = Offset(size.width / 2f, size.height / 2f)
            val staticBase = size.minDimension / 2f * 0.58f
            val substrateBrush = Brush.radialGradient(substrateColors, c, (staticBase * 1.35f).coerceAtLeast(1f))
            val specularBrush = Brush.radialGradient(
                specularColors,
                center = Offset(c.x - staticBase * 0.35f, c.y - staticBase * 0.42f),
                radius = (staticBase * 0.9f).coerceAtLeast(1f),
            )
            val causticBrush = Brush.radialGradient(
                causticColors,
                center = Offset(c.x, c.y + staticBase * 0.4f),
                radius = (staticBase * 0.95f).coerceAtLeast(1f),
            )
            val rimBrush = Brush.sweepGradient(rimColors, center = c)
            val ring1Brush = Brush.sweepGradient(ring1Colors, c)
            val ring2Brush = Brush.sweepGradient(ring2Colors, c)
            val rimStroke = Stroke(width = 1.5f * density)
            val ring1Stroke = Stroke(width = 2.5f * density)
            val ring2Stroke = Stroke(width = 1.8f * density)

            onDrawBehind {
                val energy = energyState.value
                val voice = voiceState.value
                val t = tState?.value ?: 0f
                val base = staticBase * (1 + 0.08f * voice)
                val wobble = 0.025f + 0.06f * energy + 0.12f * voice
                val a = t * 2 * PI.toFloat()

                // 1. Multi-layered Liquid Ambient Glow
                drawCircle(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.28f + 0.25f * voice + 0.12f * energy),
                            LiquidViolet.copy(alpha = 0.14f + 0.10f * energy),
                            Color.Transparent,
                        ),
                        c,
                        (base * 2.2f).coerceAtLeast(1f),
                    ),
                    radius = base * 2.2f,
                    center = c,
                )

                // 2. Liquid Glass Silhouette
                path.reset()
                for (i in 0..POINTS) {
                    val th = i * 2 * PI.toFloat() / POINTS
                    val r = base * (
                        1 + wobble * (
                            0.55f * sin(3 * th + 3 * a) +
                                0.30f * sin(5 * th - 2 * a) +
                                0.15f * sin(2 * th + 5 * a)
                            )
                        )
                    val x = c.x + r * cos(th)
                    val y = c.y + r * sin(th)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()

                // 3. Fluid Liquid Body (clipped)
                clipPath(path) {
                    drawCircle(
                        brush = substrateBrush,
                        radius = base * 1.3f,
                        center = c,
                    )

                    lights.forEachIndexed { i, spotColors ->
                        val angle = a * (i + 1) * if (i % 2 == 0) 1 else -1
                        val spot = Offset(
                            c.x + base * 0.45f * cos(angle + i * 2.1f),
                            c.y + base * 0.45f * sin(angle + i * 2.1f),
                        )
                        drawCircle(
                            Brush.radialGradient(
                                spotColors,
                                spot,
                                (base * 1.05f).coerceAtLeast(1f),
                            ),
                            radius = base * 1.3f,
                            center = c,
                        )
                    }

                    drawCircle(
                        brush = specularBrush,
                        radius = base * 1.3f,
                        center = c,
                    )

                    drawCircle(
                        brush = causticBrush,
                        radius = base * 1.1f,
                        center = c,
                    )
                }

                // 4. Liquid Glass Rim Contour
                drawPath(
                    path = path,
                    brush = rimBrush,
                    style = rimStroke,
                )

                // 5. Thinking / Connecting Dual Orbital Rings
                if (phase == Phase.Connecting || phase == Phase.Processing) {
                    val ring1 = base * 1.34f
                    val ring2 = base * 1.48f

                    rotate(t * 360f * 6) {
                        drawArc(
                            ring1Brush,
                            startAngle = 0f,
                            sweepAngle = 130f,
                            useCenter = false,
                            topLeft = Offset(c.x - ring1, c.y - ring1),
                            size = Size(ring1 * 2, ring1 * 2),
                            style = ring1Stroke,
                        )
                    }

                    rotate(-t * 360f * 4) {
                        drawArc(
                            ring2Brush,
                            startAngle = 180f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(c.x - ring2, c.y - ring2),
                            size = Size(ring2 * 2, ring2 * 2),
                            style = ring2Stroke,
                        )
                    }
                }
            }
        },
    )
}

private const val CYCLE_MS = 10_000
private const val POINTS = 100
