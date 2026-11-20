package app.shilex.android.orb

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.shilex.android.control.ControlService
import app.shilex.android.data.Settings
import app.shilex.android.ui.ElectricCyan
import app.shilex.android.ui.NeonViolet
import app.shilex.android.ui.ShilexTheme
import app.shilex.android.wake.WakeDotSize
import app.shilex.android.wake.WakeIndicator
import app.shilex.android.wake.WakeIndicatorController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Subtle top-center Liquid Glass activation dot (~12dp core, ~22dp glow, ~2.8s default)
 * shown only when the real wake word triggers. Contains no text, labels, pills, or waveforms;
 * never steals focus or touches, never wakes locked screens, and suppresses itself over
 * banking, UPI, password, and authentication screens.
 */
object WakeActivationOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var host: OverlayHost? = null
    private val cleanupRunnable = Runnable { removeWindowAndClearState() }
    private var appContext: Context? = null

    fun showBriefly(context: Context) {
        val app = context.applicationContext
        appContext = app
        main.post {
            main.removeCallbacks(cleanupRunnable)
            val wakeState = WakeIndicator.state.value
            if (!wakeState.visible) return@post

            val settings = Settings(app)
            val dotSize = WakeDotSize.fromDp(settings.wakeDotSizeDp)
            val durationMs = WakeIndicatorController.clampDurationMs(wakeState.durationMs)
            val cleanupMs = WakeIndicatorController.cleanupDelayMs(durationMs)

            val power = app.getSystemService(PowerManager::class.java)
            val keyguard = app.getSystemService(KeyguardManager::class.java)
            val control = ControlService.instance
            val fgPkg = control?.foregroundPackage().orEmpty()
            val sensitiveAuth = control?.isSensitiveAuthActive() == true
            val showOverlay = WakeIndicator.shouldShowSystemOverlay(
                indicatorEnabled = settings.wakeIndicator,
                hasOverlayPermission = AndroidSettings.canDrawOverlays(app),
                screenInteractive = power?.isInteractive == true,
                keyguardLocked = keyguard?.isKeyguardLocked == true,
                foregroundPackage = fgPkg,
                sensitiveAuthScreen = sensitiveAuth,
                inAppVisible = FloatingOrb.appVisible,
            )

            if (!showOverlay) {
                removeWindowOnly(app)
                main.postDelayed(cleanupRunnable, cleanupMs)
                return@post
            }

            removeWindowOnly(app)
            val windows = app.getSystemService(WindowManager::class.java)
            val sizePx = (dotSize.canvasDp * app.resources.displayMetrics.density).roundToInt()
            val topOffsetPx = statusBarHeightPx(app) + (TOP_MARGIN_DP * app.resources.displayMetrics.density).roundToInt()
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topOffsetPx
            }

            val overlay = OverlayHost(app)
            val triggerId = wakeState.triggerId
            overlay.open {
                ShilexTheme {
                    TinyWakeDot(
                        triggerId = triggerId,
                        durationMs = durationMs,
                        dotSize = dotSize,
                    )
                }
            }
            runCatching { windows.addView(overlay.view, params) }
                .onSuccess { host = overlay }
                .onFailure { overlay.close() }

            main.postDelayed(cleanupRunnable, cleanupMs)
        }
    }

    fun dismiss(context: Context) {
        val app = context.applicationContext
        appContext = app
        WakeIndicator.dismiss()
        main.post {
            main.removeCallbacks(cleanupRunnable)
            removeWindowOnly(app)
        }
    }

    private fun removeWindowAndClearState() {
        WakeIndicator.dismiss()
        appContext?.let(::removeWindowOnly)
    }

    private fun removeWindowOnly(context: Context) {
        val current = host ?: return
        runCatching { context.getSystemService(WindowManager::class.java).removeView(current.view) }
        current.close()
        host = null
    }

    private fun statusBarHeightPx(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    private const val TOP_MARGIN_DP = 6
}

/**
 * Subtle glowing cyan/violet Liquid Glass dot (no text, no pill, no card, no waveform).
 * Default size renders a ~12dp luminous dot with ~22dp ambient halo, fading in and out smoothly.
 */
@Composable
fun TinyWakeDot(
    triggerId: Long,
    modifier: Modifier = Modifier,
    durationMs: Long = WakeIndicatorController.DISPLAY_DURATION_MS,
    dotSize: WakeDotSize = WakeDotSize.Standard,
) {
    val effectiveDuration = WakeIndicatorController.clampDurationMs(durationMs)
    val alpha = remember(triggerId, effectiveDuration) { Animatable(0f) }
    LaunchedEffect(triggerId, effectiveDuration) {
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(WakeIndicatorController.FADE_IN_MS, easing = FastOutSlowInEasing))
        val holdMs = (effectiveDuration -
            WakeIndicatorController.FADE_IN_MS -
            WakeIndicatorController.FADE_OUT_MS).coerceAtLeast(400L)
        delay(holdMs)
        alpha.animateTo(0f, tween(WakeIndicatorController.FADE_OUT_MS, easing = LinearEasing))
    }

    val pulse by rememberInfiniteTransition(label = "wakeDotPulse").animateFloat(
        initialValue = 0.90f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val cyanAccent = ElectricCyan
    val violetAccent = NeonViolet

    Canvas(modifier = modifier.size(dotSize.canvasDp.dp)) {
        drawWakeDot(alpha = alpha.value, pulse = pulse, dotSize = dotSize, cyan = cyanAccent, violet = violetAccent)
    }
}

/**
 * Continuous live preview of [TinyWakeDot] using identical proportional core, glow, and canvas scaling
 * so adjusting the Settings slider reflects real wake-dot appearance immediately.
 */
@Composable
fun TinyWakeDotPreview(
    dotSize: WakeDotSize,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "wakeDotPreviewPulse").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "previewPulse",
    )
    val cyanAccent = ElectricCyan
    val violetAccent = NeonViolet
    Canvas(modifier = modifier.size(dotSize.canvasDp.dp)) {
        drawWakeDot(alpha = 1f, pulse = pulse, dotSize = dotSize, cyan = cyanAccent, violet = violetAccent)
    }
}

private fun DrawScope.drawWakeDot(
    alpha: Float,
    pulse: Float,
    dotSize: WakeDotSize,
    cyan: Color,
    violet: Color,
) {
    if (alpha <= 0.01f) return
    val coreRadius = (dotSize.coreDiameterDp / 2f).dp.toPx() * (0.96f + 0.08f * (pulse - 0.90f) / 0.22f)
    val glowRadius = (dotSize.glowDiameterDp / 2f).dp.toPx() * pulse

    // Soft cyan-violet Liquid Glass halo scaling proportionally with core diameter
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                cyan.copy(alpha = 0.60f * alpha),
                violet.copy(alpha = 0.34f * alpha),
                Color.Transparent,
            ),
            center = center,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = center,
    )

    // Luminous Liquid Glass dot core
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.98f * alpha),
                cyan.copy(alpha = 0.96f * alpha),
                violet.copy(alpha = 0.92f * alpha),
            ),
            center = Offset(center.x - coreRadius * 0.26f, center.y - coreRadius * 0.26f),
            radius = coreRadius * 1.32f,
        ),
        radius = coreRadius,
        center = center,
    )
}
