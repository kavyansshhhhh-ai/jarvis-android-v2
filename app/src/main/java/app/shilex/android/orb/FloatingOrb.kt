package app.shilex.android.orb

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.MainActivity
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.VoiceSession
import app.shilex.android.local.LocalPhase
import app.shilex.android.local.LocalTurn
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import app.shilex.android.data.Settings
import app.shilex.android.ui.Orb
import app.shilex.android.ui.ShilexTheme
import kotlin.math.roundToInt

/**
 * Temporary JARVIS orb shown only during an active assistant event. Tap to talk (tap again to
 * stop or cut in while speaking), double-tap to type, long-press to close, and drag to move.
 * It is hidden while JARVIS's own screen is open and while the phone is locked or asleep.
 */
object FloatingOrb {
    private var host: OverlayHost? = null
    private var params: WindowManager.LayoutParams? = null
    private var overlayVisibility = mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())
    private var pendingSync: Runnable? = null
    @Volatile private var assistantEventActive = false
    @Volatile private var appContext: Context? = null
    private val hideAfterReply = Runnable {
        assistantEventActive = false
        appContext?.let(::syncNow)
    }
    private val removeAfterExit = Runnable {
        appContext?.let { app ->
            if (shouldShowNow(app)) {
                overlayVisibility.value = true
            } else {
                removeWindowNow(app)
            }
        }
    }

    /** True while JARVIS's own screen is in front. */
    @Volatile var appVisible = false

    fun allowed(context: Context) = AndroidSettings.canDrawOverlays(context)

    /** Fast lock-free check used by Accessibility events to skip work while the orb is inactive. */
    fun isPotentiallyActive(): Boolean = assistantEventActive || host != null || overlayVisibility.value

    /** Permission and the setting enable the capability; a live assistant event is also required. */
    fun sync(context: Context) {
        val app = context.applicationContext
        appContext = app
        main.post {
            pendingSync?.let { main.removeCallbacks(it) }
            val task = Runnable {
                pendingSync = null
                syncNow(app)
            }
            pendingSync = task
            main.postDelayed(task, SYNC_DEBOUNCE_MS)
        }
    }

    /** Called when JARVIS activates or starts another user turn. */
    fun activate(context: Context) {
        val app = context.applicationContext
        appContext = app
        main.post {
            main.removeCallbacks(hideAfterReply)
            assistantEventActive = true
            syncNow(app)
        }
    }

    /** Keeps the orb through the reply, then removes the overlay after the short idle grace period. */
    fun replyFinished(context: Context) {
        val app = context.applicationContext
        appContext = app
        main.post {
            if (!assistantEventActive) return@post
            main.removeCallbacks(hideAfterReply)
            main.postDelayed(hideAfterReply, REPLY_HIDE_DELAY_MS)
        }
    }

    /** Stops the temporary overlay immediately, including when the user closes it. */
    fun dismiss(context: Context) {
        val app = context.applicationContext
        appContext = app
        main.post {
            main.removeCallbacks(hideAfterReply)
            assistantEventActive = false
            syncNow(app)
        }
    }

    private fun syncNow(app: Context) {
        val want = shouldShowNow(app)
        if (want) {
            if (host == null) show(app) else {
                main.removeCallbacks(removeAfterExit)
                overlayVisibility.value = true
            }
        } else if (host != null) {
            hide(app)
        }
    }

    private fun shouldShowNow(app: Context): Boolean {
        if (!assistantEventActive || appVisible) return false
        if (!Settings(app).floatingOrb || !allowed(app)) return false
        val screenAwakeAndUnlocked =
            app.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
        if (!screenAwakeAndUnlocked) return false
        val control = ControlService.instance
        val sensitiveScreen = control != null && (
            control.isSensitiveAuthActive() ||
                control.foregroundPackage().let { it.isNotEmpty() && ControlRules.isSensitiveOrBlockedPackage(it) }
            )
        return TemporaryOrbPolicy.shouldShow(
            assistantEventActive = assistantEventActive,
            enabled = true,
            hasOverlayPermission = true,
            appVisible = appVisible,
            sensitiveScreen = sensitiveScreen,
        )
    }

    private fun show(context: Context) {
        val windows = context.getSystemService(WindowManager::class.java)
        val size = (ORB_DP * context.resources.displayMetrics.density).roundToInt()
        val settings = Settings(context)
        val screen = context.resources.displayMetrics
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.orbX.takeIf { it >= 0 } ?: (screen.widthPixels - size)
            y = settings.orbY.takeIf { it >= 0 } ?: (screen.heightPixels * 0.6f).roundToInt()
        }
        val orb = OverlayHost(context)
        overlayVisibility.value = true
        orb.open {
            val session = VoiceSession.get(context)
            val state by session.state.collectAsStateWithLifecycle()
            val level by session.level.collectAsStateWithLifecycle()
            val localPhase by LocalTurn.get(context).phase.collectAsStateWithLifecycle()
            val orbPhase = when (localPhase) {
                LocalPhase.Listening -> Phase.Listening
                LocalPhase.Doing -> Phase.Connecting
                LocalPhase.Speaking -> Phase.Speaking
                LocalPhase.Idle -> if (state.phase == Phase.Processing) Phase.Connecting else state.phase
            }
            ShilexTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "${Branding.ASSISTANT_NAME}: tap to talk, double-tap to type, long-press to close" }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { tap(context) },
                                onDoubleTap = { TypeBox.show(context) },
                                onLongPress = { dismiss(context) },
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { settle(context) },
                                onDrag = { change, delta ->
                                    change.consume()
                                    move(context, delta.x.roundToInt(), delta.y.roundToInt())
                                },
                            )
                        },
                ) {
                    AnimatedVisibility(
                        visible = overlayVisibility.value,
                        enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.72f, animationSpec = tween(220)),
                        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.72f, animationSpec = tween(180)),
                    ) {
                        Orb(orbPhase, Modifier.fillMaxSize(), level)
                    }
                }
            }
        }
        runCatching { windows.addView(orb.view, p) }
            .onSuccess {
                host = orb
                params = p
                main.removeCallbacks(removeAfterExit)
                Diag.i("orb: shown")
            }
            .onFailure {
                overlayVisibility.value = false
                orb.close()
                Diag.w("orb: couldn't show (${it.javaClass.simpleName})")
            }
    }

    private fun hide(context: Context) {
        if (!overlayVisibility.value) return
        overlayVisibility.value = false
        main.removeCallbacks(removeAfterExit)
        main.postDelayed(removeAfterExit, EXIT_ANIMATION_MS)
        TypeBox.hide(context)
    }

    private fun removeWindowNow(context: Context) {
        val orb = host ?: return
        runCatching { context.getSystemService(WindowManager::class.java).removeView(orb.view) }
        orb.close()
        host = null
        params = null
        overlayVisibility.value = false
        TypeBox.hide(context)
        Diag.i("orb: hidden")
    }

    private fun tap(context: Context) {
        val session = VoiceSession.get(context)
        val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            !micAllowed || !session.state.value.hasKey -> openApp(context)
            !session.active -> session.start()
            session.state.value.phase == Phase.Speaking -> session.interrupt("orb")
            else -> session.stop("orb tap")
        }
    }

    private fun move(context: Context, dx: Int, dy: Int) {
        val p = params ?: return
        val screen = context.resources.displayMetrics
        p.x = (p.x + dx).coerceIn(0, screen.widthPixels - p.width)
        p.y = (p.y + dy).coerceIn(0, screen.heightPixels - p.height)
        update(context, p)
    }

    /** Slides to the nearest side and remembers the spot. */
    private fun settle(context: Context) {
        val p = params ?: return
        val width = context.resources.displayMetrics.widthPixels
        p.x = if (p.x + p.width / 2 < width / 2) 0 else width - p.width
        update(context, p)
        Settings(context).apply {
            orbX = p.x
            orbY = p.y
        }
    }

    private fun update(context: Context, p: WindowManager.LayoutParams) {
        val view = host?.view ?: return
        runCatching { context.getSystemService(WindowManager::class.java).updateViewLayout(view, p) }
    }

    /** The app asks for the mic or the key; the orb can't. */
    fun openApp(context: Context) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private const val ORB_DP = 64
    private const val REPLY_HIDE_DELAY_MS = 3_000L
    private const val EXIT_ANIMATION_MS = 220L
    private const val SYNC_DEBOUNCE_MS = 120L
}
