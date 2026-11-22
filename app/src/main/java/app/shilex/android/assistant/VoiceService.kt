package app.shilex.android.assistant

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.MainActivity
import app.shilex.android.R
import app.shilex.android.audio.AudioPlaybackState
import app.shilex.android.data.Settings
import app.shilex.android.local.LocalTurn
import app.shilex.android.orb.WakeActivationOverlay
import app.shilex.android.wake.WakeIndicator
import app.shilex.android.wake.WakeListener
import app.shilex.android.wake.WakePolicy
import app.shilex.android.wake.WakeState
import app.shilex.android.wake.WakeWordModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps JARVIS running while the user is in other apps or idle, with the visible foreground
 * microphone notification Android requires. Two modes: an active voice session/local turn,
 * or between sessions, the offline on-device wake-word listener ("Jarvis" / "Hey Jarvis").
 */
class VoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wake: WakeListener
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWakeAt = 0L
    private var activeTone: ToneGenerator? = null
    private var channelsCreated = false

    // Voice setup records with its own mic; the wake listener waits meanwhile.
    @Volatile private var setupActive = false

    override fun onCreate() {
        super.onCreate()
        wake = WakeListener(
            context = this,
            onState = { state ->
                if (!setupActive && !VoiceSession.get(this).active && !LocalTurn.get(this).active) {
                    _wakeState.value = state
                }
            },
            onWake = ::onWake,
        )
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LocalTurn.get(this).stop()
                VoiceSession.get(this).stop("notification Stop")
            }
            ACTION_WAKE_OFF -> {
                Settings(this).wakeWord = false
                Diag.i("wake: turned off from the notification")
            }
            ACTION_SHUTDOWN -> {
                VoiceSession.get(this).shutdownCompletely("notification shutdown")
                return START_NOT_STICKY
            }
        }
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(RESUME_WAKE_TOKEN)
        main.removeCallbacksAndMessages(TONE_TOKEN)
        releaseToneGenerator()
        wake.stop()
        WakeActivationOverlay.dismiss(this)
        releaseWakeLock()
        syncState(this)
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Session running -> session mode; else wake word if enabled; else stop. */
    fun update() {
        main.removeCallbacksAndMessages(RESUME_WAKE_TOKEN)
        if (setupActive) {
            wake.stop()
            _wakeState.value = WakeState.PausedSession
            return
        }
        if (VoiceSession.get(this).active || LocalTurn.get(this).active) {
            wake.stop()
            _wakeState.value = WakeState.PausedSession
            show(sessionNotification())
            return
        }
        if (wakeWordWanted(this)) {
            show(wakeNotification())
            _wakeState.value = WakeState.Starting
            val resumeDelay = if (AudioPlaybackState.isSpeakingOrTail()) AudioPlaybackState.TTS_ECHO_TAIL_MS else MIC_HANDOVER_MS
            // Let the session's mic & any TTS echo tail settle before the wake listener reopens.
            main.postDelayed(
                {
                    if (!VoiceSession.get(this).active &&
                        !LocalTurn.get(this).active &&
                        !setupActive &&
                        wakeWordWanted(this)
                    ) {
                        if (!wake.start()) {
                            Diag.w("wake: couldn't start")
                            syncState(this)
                        }
                    }
                },
                RESUME_WAKE_TOKEN,
                resumeDelay,
            )
            return
        }
        wake.stop()
        WakeActivationOverlay.dismiss(this)
        if (!foreground) {
            // Android 12/13+ throws ForegroundServiceDidNotStartInTimeException if started via
            // startForegroundService and stopped via stopSelf without calling startForeground first.
            show(wakeNotification())
        }
        if (foreground) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            foreground = false
        }
        syncState(this)
        stopSelf()
    }

    private fun onWake() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val now = SystemClock.elapsedRealtime()
        val sessionRunning = VoiceSession.get(this).active || LocalTurn.get(this).active || setupActive
        val speaking = AudioPlaybackState.isSpeakingOrTail(now)
        when (
            WakePolicy.evaluateActivation(
                nowMs = now,
                lastWakeMs = lastWakeAt,
                keyguardLocked = keyguard.isKeyguardLocked,
                hasMicPermission = hasMic,
                sessionActive = sessionRunning,
                assistantSpeaking = speaking,
            )
        ) {
            WakePolicy.Decision.NoMicPermission -> {
                Diag.w("wake: mic permission missing, pausing")
                update()
                return
            }
            WakePolicy.Decision.Locked -> {
                Diag.i("wake: phone locked, ignored")
                update()
                return
            }
            WakePolicy.Decision.SessionAlreadyActive -> {
                Diag.i("wake: ignored (session already active)")
                update()
                return
            }
            WakePolicy.Decision.AssistantSpeaking -> {
                Diag.i("wake: ignored (assistant speaking)")
                update()
                return
            }
            WakePolicy.Decision.Cooldown -> {
                Diag.i("wake: cooldown active, ignored")
                update()
                return
            }
            WakePolicy.Decision.Activate -> {
                lastWakeAt = now
            }
        }

        // Briefly hold a partial wake lock so screen-off handover completes cleanly.
        acquireHandoverWakeLock()
        val interactive = runCatching { getSystemService(PowerManager::class.java)?.isInteractive == true }.getOrDefault(true)
        val settings = Settings(this)
        if (
            WakeIndicator.onWakeDetected(
                nowMs = now,
                enabled = settings.wakeIndicator,
                keyguardLocked = keyguard.isKeyguardLocked,
                screenInteractive = interactive,
                durationMs = settings.wakeDotDurationMs,
                sessionActive = sessionRunning,
                assistantSpeaking = speaking,
            )
        ) {
            WakeActivationOverlay.showBriefly(this)
        }
        playWakeTone()
        if (Settings(this).localCommands && LocalTurn.get(this).start()) return
        VoiceSession.get(this).start()
    }

    private fun playWakeTone() {
        main.removeCallbacksAndMessages(TONE_TOKEN)
        releaseToneGenerator()
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_SYSTEM, 70)
            activeTone = tone
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, WAKE_TONE_DURATION_MS)
            main.postDelayed(
                {
                    if (activeTone === tone) activeTone = null
                    runCatching { tone.release() }
                },
                TONE_TOKEN,
                WAKE_TONE_RELEASE_DELAY_MS,
            )
        }.onFailure {
            releaseToneGenerator()
        }
    }

    private fun releaseToneGenerator() {
        val tone = activeTone ?: return
        activeTone = null
        runCatching { tone.stopTone() }
        runCatching { tone.release() }
    }

    private fun acquireHandoverWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake_handover").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            lock.acquire(WakePolicy.HANDOVER_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun show(notification: Notification) {
        if (!foreground) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            val ok = runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            }.onFailure { err ->
                Diag.w("service: startForeground failed (${err.javaClass.simpleName})")
            }.isSuccess
            foreground = ok
        } else {
            runCatching {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun sessionNotification() = builder(CHANNEL)
        .setContentTitle("${Branding.ASSISTANT_NAME} is listening")
        .setContentText("Talk any time. It stops after 50 quiet seconds.")
        .addAction(0, "Stop", action(ACTION_STOP, 1))
        .build()

    // Visible ongoing microphone notification required on Android 13+ while wake listening is active.
    private fun wakeNotification() = builder(STANDBY_CHANNEL)
        .setContentTitle("Say \"${WakeWordModel.PHRASE}\" to talk")
        .setContentText("Listening offline on this phone. Nothing is saved or sent until you say \"${WakeWordModel.PHRASE}\".")
        .setSilent(true)
        .addAction(0, "Turn off", action(ACTION_WAKE_OFF, 2))
        .build()

    private fun builder(channel: String): NotificationCompat.Builder {
        if (!channelsCreated) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while ${Branding.ASSISTANT_NAME} can hear you."
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(STANDBY_CHANNEL, "Wake Word Standby", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while ${Branding.ASSISTANT_NAME} listens locally for the wake word."
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            channelsCreated = true
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
    }

    private fun action(name: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, VoiceService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL = "listening"
        private const val STANDBY_CHANNEL = "wake_standby"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.shilex.android.STOP"
        private const val ACTION_WAKE_OFF = "app.shilex.android.WAKE_OFF"
        private const val ACTION_SHUTDOWN = "app.shilex.android.SHUTDOWN"
        private const val MIC_HANDOVER_MS = 280L
        private const val WAKE_TONE_DURATION_MS = 130
        private const val WAKE_TONE_RELEASE_DELAY_MS = 260L
        private val RESUME_WAKE_TOKEN = Any()
        private val TONE_TOKEN = Any()

        @Volatile private var instance: VoiceService? = null

        private val _wakeState = MutableStateFlow(WakeState.Off)
        val wakeState: StateFlow<WakeState> = _wakeState.asStateFlow()

        fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun wakeWordWanted(context: Context): Boolean =
            Settings(context).wakeWord &&
                WakeWordModel.available(context) &&
                hasMicPermission(context)

        /** Updates [wakeState] when the service is stopped or blocked by missing permissions. */
        fun syncState(context: Context) {
            val settings = Settings(context)
            _wakeState.value = when {
                VoiceSession.get(context).active || LocalTurn.get(context).active -> WakeState.PausedSession
                !settings.wakeWord -> WakeState.Off
                !WakeWordModel.available(context) -> WakeState.Unavailable
                !hasMicPermission(context) -> WakeState.PermissionNeeded
                instance != null -> _wakeState.value
                else -> WakeState.Off
            }
        }

        /**
         * Starts the service, or updates it if it's already running. A fresh start must
         * happen while the app is on screen (Android blocks starting a mic service from the
         * background); a running service can switch modes at any time.
         */
        fun start(context: Context) {
            instance?.let {
                it.update()
                return
            }
            if (!VoiceSession.get(context).active && !LocalTurn.get(context).active && !wakeWordWanted(context)) {
                syncState(context)
                return
            }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java)) }
                .onFailure {
                    Diag.w("service: could not start (${it.javaClass.simpleName})")
                    syncState(context)
                }
        }

        /** Pauses (or resumes) the wake listener while voice setup uses the mic. */
        fun voiceSetup(active: Boolean) {
            instance?.let {
                it.setupActive = active
                it.update()
            }
        }

        /** The session ended: go back to waiting for the wake word, or stop. */
        fun stop(context: Context) {
            val running = instance
            if (running != null) {
                running.update()
            } else {
                syncState(context)
            }
        }

        /** Completely tears down wake listening, foreground service, notifications, and overlays. */
        fun shutdownNow(context: Context) {
            WakeIndicator.dismiss()
            WakeActivationOverlay.dismiss(context)
            app.shilex.android.wake.VoiceIndicator.off("shutdown")
            val running = instance
            if (running != null) {
                running.main.removeCallbacksAndMessages(RESUME_WAKE_TOKEN)
                running.wake.stop()
                running.releaseWakeLock()
                if (running.foreground) {
                    ServiceCompat.stopForeground(running, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    running.foreground = false
                }
                running.stopSelf()
            }
            syncState(context)
        }
    }
}
