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
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

    val energy by animateFloatAsState(
        when (phase) {
            Phase.Off -> 0.05f
            Phase.Connecting, Phase.Processing -> 0.35f
            Phase.Listening -> 0.55f
            Phase.Speaking -> 0.95f
        },
        tween(600),
        label = "energy",
    )

    val voice by animateFloatAsState(level, spring(stiffness = Spring.StiffnessLow), label = "voice")

    val t = if (phase == Phase.Off) {
        0f
    } else {
        rememberInfiniteTransition(label = "orb").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "t",
        ).value
    }

    val lights = remember(accent) { listOf(accent, LiquidViolet, LiquidPink) }
    val path = remember { Path() }

    Canvas(modifier) {
        val c = center
        val base = size.minDimension / 2 * 0.58f * (1 + 0.08f * voice)
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
                base * 2.2f,
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
            // Deep cosmic fluid substrate
            drawCircle(
                Brush.radialGradient(
                    listOf(LiquidBlue, LiquidDeep),
                    c,
                    base * 1.25f,
                ),
                radius = base * 1.3f,
                center = c,
            )

            // Drifting liquid chromatic spots
            lights.forEachIndexed { i, color ->
                val angle = a * (i + 1) * if (i % 2 == 0) 1 else -1
                val spot = Offset(
                    c.x + base * 0.45f * cos(angle + i * 2.1f),
                    c.y + base * 0.45f * sin(angle + i * 2.1f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)),
                        spot,
                        base * 1.05f,
                    ),
                    radius = base * 1.3f,
                    center = c,
                )
            }

            // Glass specular top-left highlight
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = highlight), Color.Transparent),
                    center = Offset(c.x - base * 0.35f, c.y - base * 0.42f),
                    radius = base * 0.85f,
                ),
                radius = base * 1.3f,
                center = c,
            )

            // Bottom glass caustic rim reflection
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.Transparent, LiquidCyan.copy(alpha = 0.35f)),
                    center = Offset(c.x, c.y + base * 0.4f),
                    radius = base * 0.9f,
                ),
                radius = base * 1.1f,
                center = c,
            )
        }

        // 4. Liquid Glass Rim Contour
        drawPath(
            path = path,
            brush = Brush.sweepGradient(
                listOf(
                    Color.White.copy(alpha = 0.6f),
                    accent.copy(alpha = 0.8f),
                    LiquidViolet.copy(alpha = 0.4f),
                    Color.White.copy(alpha = 0.7f),
                ),
                center = c,
            ),
            style = Stroke(width = 1.5f * density),
        )

        // 5. Thinking / Connecting Dual Orbital Rings
        if (phase == Phase.Connecting || phase == Phase.Processing) {
            val ring1 = base * 1.34f
            val ring2 = base * 1.48f

            // Inner cyan ring
            rotate(t * 360f * 6) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, accent, Color.Transparent), c),
                    startAngle = 0f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(c.x - ring1, c.y - ring1),
                    size = Size(ring1 * 2, ring1 * 2),
                    style = Stroke(width = 2.5f * density),
                )
            }

            // Outer violet counter-rotating ring
            rotate(-t * 360f * 4) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, LiquidViolet, Color.Transparent), c),
                    startAngle = 180f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(c.x - ring2, c.y - ring2),
                    size = Size(ring2 * 2, ring2 * 2),
                    style = Stroke(width = 1.8f * density),
                )
            }
        }
    }
}

private const val CYCLE_MS = 10_000
private const val POINTS = 100
