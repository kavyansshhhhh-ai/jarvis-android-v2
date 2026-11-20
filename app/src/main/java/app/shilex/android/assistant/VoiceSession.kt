package app.shilex.android.assistant

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.SystemClock
import app.shilex.android.Diag
import app.shilex.android.audio.AudioPlaybackState
import app.shilex.android.audio.MicRecorder
import app.shilex.android.audio.MusicPause
import app.shilex.android.audio.Speaker
import app.shilex.android.audio.TalkOver
import app.shilex.android.audio.VOICE_LEVEL
import app.shilex.android.audio.WaveStop
import app.shilex.android.audio.headphonesConnected
import app.shilex.android.audio.pcmLevel
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenVision
import app.shilex.android.data.KeyCheckResult
import app.shilex.android.data.KeyVault
import app.shilex.android.data.PersonalProfileResolver
import app.shilex.android.data.PersonalProfileStore
import app.shilex.android.data.Settings
import app.shilex.android.data.checkGeminiKey
import app.shilex.android.learn.Habits
import app.shilex.android.live.LiveClient
import app.shilex.android.live.LiveEvent
import app.shilex.android.live.LiveProtocol
import app.shilex.android.local.LocalTurn
import app.shilex.android.orb.WakeActivationOverlay
import app.shilex.android.orb.FloatingOrb
import app.shilex.android.task.ActiveTaskContext
import app.shilex.android.task.CancellationStatus
import app.shilex.android.task.TaskTracker
import app.shilex.android.wake.VoiceIndicator
import app.shilex.android.wake.WakeIndicator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class Phase { Off, Connecting, Processing, Listening, Speaking }

data class UiState(
    val hasKey: Boolean,
    val phase: Phase = Phase.Off,
    val lines: List<Line> = emptyList(),
    val problem: String? = null,
    /** A permission a tool needs; the screen asks for it, then calls permissionAnswered. */
    val permissionNeeded: String? = null,
    /** Incremented when Android blocks screenshot capture and Photo Picker is offered instead. */
    val photoPickRequestId: Long = 0L,
    val photoPickQuestion: String = "",
    /** Non-zero when Android's MediaStore delete confirmation dialog must be shown to the user. */
    val galleryDeleteRequestId: Long = 0L,
    val galleryDeleteIntentSender: IntentSender? = null,
)

/**
 * Voice session: mic → Gemini Live → speaker, with reconnects and an idle timeout. One per
 * process, so it keeps running in VoiceService after the user leaves the app.
 */
class VoiceSession private constructor(private val app: Application) : LiveClient.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val vault = KeyVault(app)
    private val settings = Settings(app)
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val client = LiveClient(http)
    private val speaker = Speaker.get(app)
    private val mic = MicRecorder(::onMicChunk)
    private val wave = WaveStop(app) { interrupt("a wave") }
    private val talkOver = TalkOver(speaker)
    private val transcript = Transcript()
    @Volatile private var pendingGalleryDelete: CompletableDeferred<Boolean>? = null

    private val tools = ToolRunner(
        app,
        http,
        scope,
        answer = { client.send(LiveProtocol.toolResponseMessage(it)) },
        askPermission = { permission -> _state.update { it.copy(permissionNeeded = permission) } },
        onScreen = { screenVisible },
        note = { if (wanted) client.send(LiveProtocol.appNoteMessage(it)) },
        endSoon = ::endAfterReply,
        onPhotoPick = { question ->
            _state.update {
                it.copy(
                    photoPickRequestId = System.currentTimeMillis(),
                    photoPickQuestion = question,
                )
            }
        },
        onGalleryDelete = { sender -> requestGalleryDeleteConfirmation(sender) },
        onShutdown = { reason -> shutdownCompletely(reason) },
    )
    private val musicPause = MusicPause(app)

    /** Set by MainActivity; apps can only be opened directly while JARVIS is on screen. */
    @Volatile var screenVisible = false

    private val _state = MutableStateFlow(UiState(hasKey = vault.read(KeyVault.GEMINI_KEY) != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Loudness of the user's voice or the reply, 0..1, so the orb can move with it. */
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var wanted = false
    @Volatile private var musicAsked = false // a tool started music: don't pause it again
    @Volatile private var pendingText: String? = null

    /** True from start() until stop(). */
    val active: Boolean get() = wanted
    @Volatile private var resumeHandle: String? = null
    @Volatile private var droppingReply = false
    @Volatile private var lastActivity = 0L
    private val turnLock = Any()
    private var retries = 0
    private var watchdog: Job? = null
    private var replyWaitJob: Job? = null
    private var endAfterReplyJob: Job? = null
    private var connectJob: Job? = null

    // Timings for the diagnostic log (never the words themselves).
    private var connectAt = 0L
    @Volatile private var heardUser = false
    @Volatile private var voiceAt = 0L
    @Volatile private var replyStarted = false
    @Volatile private var mutedChunks = 0
    @Volatile private var loudest = 0
    @Volatile private var turnReceivedAudio = false
    private data class BufferedReplyAudio(val pcm: ByteArray, val sampleRate: Int)
    private val bufferedReplyAudioLock = Any()
    private val bufferedReplyAudio = mutableListOf<BufferedReplyAudio>()
    private var bufferedReplyAudioBytes = 0
    private var bufferedReplyAudioOverflowed = false
    private val lastAssistantTurnText = StringBuilder()
    private val lastUserTurnText = StringBuilder()

    suspend fun saveKey(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "Paste your Gemini key first."
        return when (val result = checkGeminiKey(http, trimmed, settings.model)) {
            KeyCheckResult.Ok -> {
                vault.write(KeyVault.GEMINI_KEY, trimmed)
                _state.update { it.copy(hasKey = true, problem = null) }
                null
            }
            is KeyCheckResult.Problem -> result.message
        }
    }

    fun forgetKey() {
        stop("key removed")
        vault.remove(KeyVault.GEMINI_KEY)
        _state.update { it.copy(hasKey = false) }
    }

    fun sendTextMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _state.update {
            it.copy(
                phase = if (it.phase == Phase.Off) Phase.Listening else it.phase,
                lines = transcript.add(fromUser = true, trimmed),
            )
        }
        transcript.endTurn()
        tools.userSpoke(trimmed)
        if (wanted) {
            if (_state.value.phase == Phase.Connecting) {
                pendingText = trimmed
            } else {
                runCatching { client.send(LiveProtocol.userTextMessage(trimmed)) }
            }
        } else {
            start(trimmed)
        }
    }

    /** Call only after the mic is allowed. [firstText], heard or typed, goes to Gemini as the user's turn. */
    fun start(firstText: String? = null) {
        if (firstText != null) tools.userSpoke(firstText)
        // Typed during a session (floating orb): send now, or once connected.
        if (wanted && firstText != null) return Unit.also {
            if (_state.value.phase == Phase.Connecting) pendingText = firstText else client.send(LiveProtocol.userTextMessage(firstText))
        }
        pendingText = firstText
        // Music playing would drown the user's voice; it resumes when the session ends.
        musicPause.pause()
        if (wanted) return
        wanted = true
        FloatingOrb.activate(app)
        retries = 0
        resumeHandle = null
        resetTurnLog()
        musicAsked = false
        Diag.i("session: start (headphones=${headphonesConnected(app)})")
        talkOver.reset(newConversation = true)
        VoiceService.start(app)
        ControlService.instance?.overlay?.stopped = false
        lastActivity = SystemClock.elapsedRealtime()
        VoiceIndicator.onProcessing()
        connect()
        watchdog = scope.launch {
            var musicChecks = 0
            var repaused = false
            while (isActive) {
                delay(WATCH_MS)
                // Music sounds like speech and keeps a session open forever. YouTube Music sometimes resumes by itself
                // after pausing, so pause it once more; if it plays again the user wants it, so end and let "Hey Jarvis" listen.
                musicChecks = if (musicPause.othersPlaying() && !speaker.isAudible(tailMs = 1_500)) musicChecks + 1 else 0
                if (musicAsked) {
                    musicChecks = 0
                } else if (musicChecks >= 1 && !repaused) {
                    repaused = true
                    musicChecks = 0
                    Diag.i("audio: music resumed by itself, pausing again")
                    musicPause.pause()
                } else if (musicChecks >= 2) {
                    stop("music playing")
                } else if (SystemClock.elapsedRealtime() - lastActivity > IDLE_MS) {
                    VoiceIndicator.onIdleTimeout("50 quiet seconds")
                    stop("idle for 50 seconds")
                    _state.update { it.copy(problem = "Stopped listening after 50 quiet seconds.") }
                }
            }
        }
    }

    /** Ends the session; [reason] goes to the diagnostic log. */
    fun stop(reason: String) {
        if (wanted) Diag.i("session: stop ($reason)")
        wanted = false
        FloatingOrb.dismiss(app)
        wave.watch(false)
        pendingGalleryDelete?.complete(false)
        pendingGalleryDelete = null
        VoiceService.stop(app)
        musicPause.resume()
        tools.reset()
        ControlService.instance?.overlay?.cancelPending()
        watchdog?.cancel()
        watchdog = null
        connectJob?.cancel()
        connectJob = null
        replyWaitJob?.cancel()
        replyWaitJob = null
        endAfterReplyJob?.cancel()
        endAfterReplyJob = null
        mic.stop()
        client.close()
        speaker.flush()
        clearRetainedReplyAudio()
        synchronized(turnLock) {
            turnReceivedAudio = false
            droppingReply = false
            lastAssistantTurnText.clear()
            lastUserTurnText.clear()
        }
        transcript.endTurn()
        _state.update {
            it.copy(
                phase = Phase.Off,
                photoPickRequestId = 0L,
                galleryDeleteRequestId = 0L,
                galleryDeleteIntentSender = null,
            )
        }
        _level.value = 0f
        VoiceIndicator.off(reason)
    }

    /**
     * Full explicit assistant shutdown: stops speaking, mic, background wake-word standby,
     * foreground service, overlays, indicators, and clears transient task state.
     */
    fun shutdownCompletely(reason: String = "user requested shutdown") {
        Diag.i("session: complete shutdown ($reason)")
        ActiveTaskContext.cancel(CancellationStatus.Cancelled, reason)
        settings.wakeWord = false
        LocalTurn.get(app).stop(releaseModel = true)
        stop(reason)
        WakeIndicator.dismiss()
        WakeActivationOverlay.dismiss(app)
        VoiceIndicator.off(reason)
        VoiceService.shutdownNow(app)
    }

    private suspend fun requestGalleryDeleteConfirmation(intentSender: IntentSender): Boolean {
        pendingGalleryDelete?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingGalleryDelete = deferred
        _state.update {
            it.copy(
                galleryDeleteRequestId = System.currentTimeMillis(),
                galleryDeleteIntentSender = intentSender,
            )
        }
        return deferred.await()
    }

    fun onGalleryDeleteResult(confirmed: Boolean) {
        _state.update { it.copy(galleryDeleteRequestId = 0L, galleryDeleteIntentSender = null) }
        pendingGalleryDelete?.complete(confirmed)
        pendingGalleryDelete = null
    }

    /** Handles a user-selected photo from Android's Photo Picker fallback cleanly via Gemini Vision. */
    fun onPhotoPicked(uri: Uri?) {
        val question = _state.value.photoPickQuestion
        _state.update { it.copy(photoPickRequestId = 0L, photoPickQuestion = "") }
        if (uri == null) return
        val key = vault.read(KeyVault.GEMINI_KEY)
        if (key == null) {
            _state.update { it.copy(problem = "Add your Gemini API key in Settings to describe photos.") }
            return
        }
        scope.launch {
            val outcome = ScreenVision(http, key).describeUri(app, uri, question)
            when (outcome) {
                is ScreenVision.Outcome.Described -> {
                    if (wanted) {
                        client.send(LiveProtocol.appNoteMessage("User picked this photo from gallery. Description: ${outcome.summary}"))
                    } else {
                        _state.update { it.copy(lines = transcript.add(fromUser = false, outcome.summary)) }
                        transcript.endTurn()
                    }
                }
                is ScreenVision.Outcome.Failed -> {
                    _state.update { it.copy(problem = outcome.message) }
                }
            }
        }
    }

    private fun loudness(level: Int) = (level / LOUD_LEVEL).coerceIn(0f, 1f)

    /** Ends the session once the reply in progress has been spoken. */
    private fun endAfterReply() {
        musicAsked = true
        endAfterReplyJob?.cancel()
        endAfterReplyJob = scope.launch {
            delay(300)
            while (isActive && wanted && (speaker.hasPendingOrActiveSpeech() || _state.value.phase == Phase.Connecting || _state.value.phase == Phase.Processing)) {
                delay(100)
            }
            if (isActive && wanted) {
                stop("music started")
            }
        }
    }

    /** Tap or voice barge-in while speaking: silence this reply immediately and return to clean listening. */
    fun interrupt(how: String = "tap") {
        if (_state.value.phase != Phase.Speaking && _state.value.phase != Phase.Processing && !speaker.hasPendingOrActiveSpeech()) return
        Diag.i("reply: stopped by $how")
        replyWaitJob?.cancel()
        replyWaitJob = null
        endAfterReplyJob?.cancel()
        endAfterReplyJob = null
        wave.watch(false)
        synchronized(turnLock) {
            droppingReply = true
            turnReceivedAudio = false
            lastAssistantTurnText.clear()
        }
        speaker.flush()
        clearRetainedReplyAudio()
        AudioPlaybackState.onLocalTtsEnded()
        AudioPlaybackState.onLiveSpeakerState(active = false)
        lastActivity = SystemClock.elapsedRealtime()
        VoiceIndicator.onInterrupted(how)
        _level.value = 0f
        _state.update { it.copy(phase = Phase.Listening) }
        VoiceIndicator.onListening(0f)
    }

    /** The user answered a tool's permission request; tell the model so it can carry on. */
    fun permissionAnswered(granted: Boolean) {
        val permission = _state.value.permissionNeeded ?: return
        _state.update { it.copy(permissionNeeded = null) }
        if (wanted) client.send(LiveProtocol.appNoteMessage(ToolRunner.permissionNote(permission, granted)))
    }

    fun micUnavailable() {
        stop("mic permission refused")
        _state.update { it.copy(problem = "The microphone is needed to talk. Allow it in the app's settings.") }
    }

    private fun connect() {
        val key = vault.read(KeyVault.GEMINI_KEY)
        if (key == null) {
            stop("no gemini key")
            _state.update {
                it.copy(
                    hasKey = false,
                    phase = Phase.Off,
                    problem = "Add your Gemini API key in Settings to handle cloud requests.",
                )
            }
            return
        }
        _state.update { it.copy(phase = Phase.Connecting, problem = null) }
        connectAt = SystemClock.elapsedRealtime()
        val retryNum = synchronized(turnLock) { retries + 1 }
        Diag.i("socket: connecting (try $retryNum, resume=${resumeHandle != null}, model=${settings.model})")
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            val profileSummary = PersonalProfileResolver.assistantContextSummary(PersonalProfileStore(app).load())
            val habitSummary = if (settings.learning) Habits.get(app).summary(java.time.LocalTime.now().hour) else ""
            val activeContext = ActiveTaskContext.promptSummary()
            val setup = LiveProtocol.Setup(
                model = settings.model,
                systemInstruction = Instructions.build(
                    settings.replyLanguage,
                    java.time.ZonedDateTime.now(),
                    habitSummary,
                    profileSummary,
                    activeContext = activeContext,
                ),
                voice = settings.voice,
                languages = settings.speechLanguages,
                resumeHandle = resumeHandle,
                tools = tools.declarations(),
            )
            if (!isActive || !wanted) return@launch
            client.connect(key, setup, this@VoiceSession)
        }
    }

    override fun onEvent(event: LiveEvent) {
        when (event) {
            LiveEvent.SetupComplete -> {
                Diag.i("socket: ready in ${SystemClock.elapsedRealtime() - connectAt} ms")
                synchronized(turnLock) { retries = 0 }
                if (!mic.start()) {
                    stop("mic busy")
                    _state.update { it.copy(problem = "Another app is using the microphone.") }
                    return
                }
                _state.update { it.copy(phase = Phase.Listening) }
                VoiceIndicator.onListening(0f)
                pendingText?.let {
                    pendingText = null
                    client.send(LiveProtocol.userTextMessage(it))
                }
            }
            is LiveEvent.Audio -> if (!droppingReply) {
                FloatingOrb.activate(app)
                val firstAudioWait = synchronized(turnLock) {
                    turnReceivedAudio = true
                    if (!replyStarted) {
                        replyStarted = true
                        if (voiceAt > 0) "${SystemClock.elapsedRealtime() - voiceAt} ms after you stopped talking" else "with no voice heard"
                    } else null
                }
                if (firstAudioWait != null) {
                    Diag.i("reply: first audio $firstAudioWait")
                }
                val amp = loudness(pcmLevel(event.pcm))
                _level.value = amp
                if (LiveResponseVoicePolicy.shouldUseSelectedVoice(settings.ttsVoiceId)) {
                    if (settings.speakResponses) retainReplyAudio(event.pcm, event.sampleRate)
                    // Wait for Gemini's transcription and render it with the voice selected in Settings.
                    _state.update { it.copy(phase = Phase.Processing) }
                    VoiceIndicator.onProcessing()
                } else {
                    speaker.play(event.pcm, event.sampleRate)
                    _state.update { it.copy(phase = Phase.Speaking) }
                    VoiceIndicator.onSpeaking(amp)
                    wave.watch(settings.waveToStop)
                }
            }
            is LiveEvent.UserText -> {
                FloatingOrb.activate(app)
                lastActivity = SystemClock.elapsedRealtime()
                val firstHeard: Boolean
                val accumulatedText: String
                synchronized(turnLock) {
                    firstHeard = !heardUser
                    heardUser = true
                    lastUserTurnText.append(' ').append(event.text)
                    accumulatedText = lastUserTurnText.toString().trim()
                }
                if (firstHeard) {
                    Diag.i("user: speech heard")
                    TaskTracker.heard()
                }
                tools.userSpoke(accumulatedText)
                _state.update { it.copy(lines = transcript.add(fromUser = true, event.text)) }
            }
            is LiveEvent.AssistantText -> if (!droppingReply) {
                FloatingOrb.activate(app)
                lastActivity = SystemClock.elapsedRealtime()
                synchronized(turnLock) { lastAssistantTurnText.append(event.text) }
                _state.update { it.copy(lines = transcript.add(fromUser = false, event.text)) }
            }
            LiveEvent.Interrupted -> {
                // The server cut the old reply itself; audio after this is a new one.
                Diag.i("reply: interrupted by the user's voice")
                replyWaitJob?.cancel()
                replyWaitJob = null
                endAfterReplyJob?.cancel()
                endAfterReplyJob = null
                wave.watch(false)
                synchronized(turnLock) {
                    droppingReply = false
                    replyStarted = false
                    turnReceivedAudio = false
                    lastAssistantTurnText.clear()
                    lastUserTurnText.clear()
                }
                clearRetainedReplyAudio()
                speaker.flush()
                transcript.endTurn()
                lastActivity = SystemClock.elapsedRealtime()
                VoiceIndicator.onInterrupted("server barge-in")
                _state.update { it.copy(phase = Phase.Listening) }
                VoiceIndicator.onListening(0f)
            }
            LiveEvent.TurnComplete -> {
                val (wasDropping, textToSpeak, receivedAudio) = synchronized(turnLock) {
                    if (droppingReply) {
                        droppingReply = false
                        turnReceivedAudio = false
                        lastAssistantTurnText.clear()
                        Triple(true, "", false)
                    } else {
                        val txt = lastAssistantTurnText.toString()
                        val rec = turnReceivedAudio
                        turnReceivedAudio = false
                        lastAssistantTurnText.clear()
                        lastUserTurnText.clear()
                        droppingReply = false
                        Triple(false, txt, rec)
                    }
                }
                if (wasDropping) {
                    clearRetainedReplyAudio()
                    return
                }
                replyWaitJob?.cancel()
                replyWaitJob = null
                speaker.endReply()
                val retainedAudio = takeRetainedReplyAudio()
                val selectedLocalVoice = LiveResponseVoicePolicy.shouldUseSelectedVoice(settings.ttsVoiceId)
                logTurnEnd()
                transcript.endTurn()

                // If Gemini Live stream sent text reply but no audio chunks (or audio dropped), fallback to local TTS!
                if ((!receivedAudio || selectedLocalVoice) && textToSpeak.isNotBlank() && settings.speakResponses) {
                    Diag.i("reply: speaking assistant text with the selected local voice")
                    _state.update { it.copy(phase = Phase.Speaking) }
                    VoiceIndicator.onSpeaking(0.45f)
                    wave.watch(settings.waveToStop)
                    speaker.speak(
                        text = textToSpeak,
                        onInterrupted = {
                            wave.watch(false)
                            if (wanted && _state.value.phase == Phase.Speaking) {
                                _state.update { it.copy(phase = Phase.Listening) }
                                VoiceIndicator.onListening(0f)
                            }
                        },
                    ) {
                        replyWaitJob?.cancel()
                        replyWaitJob = scope.launch {
                            while (isActive && wanted && speaker.hasPendingOrActiveSpeech()) delay(100)
                            if (!isActive || !wanted) return@launch
                            wave.watch(false)
                            if (_state.value.phase == Phase.Speaking) {
                                _state.update { it.copy(phase = Phase.Listening) }
                                VoiceIndicator.onListening(0f)
                            }
                            FloatingOrb.replyFinished(app)
                            tools.openPending()
                        }
                    }
                } else if (selectedLocalVoice && settings.speakResponses && receivedAudio && retainedAudio.chunks.isNotEmpty()) {
                    Diag.w("reply: selected local voice had no transcript; replaying retained Gemini audio")
                    _state.update { it.copy(phase = Phase.Speaking) }
                    VoiceIndicator.onSpeaking(0.45f)
                    retainedAudio.chunks.forEach { chunk -> speaker.play(chunk.pcm, chunk.sampleRate) }
                    speaker.endReply()
                    wave.watch(settings.waveToStop)
                    replyWaitJob = scope.launch {
                        while (isActive && wanted && speaker.hasPendingOrActiveSpeech()) delay(100)
                        if (!isActive || !wanted) return@launch
                        wave.watch(false)
                        if (_state.value.phase == Phase.Speaking) {
                            _state.update { it.copy(phase = Phase.Listening) }
                            VoiceIndicator.onListening(0f)
                        }
                        FloatingOrb.replyFinished(app)
                        tools.openPending()
                    }
                } else {
                    if (selectedLocalVoice && settings.speakResponses && receivedAudio && textToSpeak.isBlank()) {
                        val detail = if (retainedAudio.overflowed) {
                            "Gemini provided no text for the selected voice, and its audio fallback exceeded the safe memory limit."
                        } else {
                            "Gemini provided no text for the selected voice to speak."
                        }
                        _state.update { it.copy(problem = detail) }
                        Diag.w("reply: selected local voice unavailable because the audio response had no transcription")
                    }
                    replyWaitJob = scope.launch {
                        while (isActive && wanted && speaker.hasPendingOrActiveSpeech()) delay(100)
                        if (!isActive || !wanted) return@launch
                        wave.watch(false)
                        if (_state.value.phase == Phase.Speaking) {
                            _state.update { it.copy(phase = Phase.Listening) }
                            VoiceIndicator.onListening(0f)
                        }
                        FloatingOrb.replyFinished(app)
                        tools.openPending()
                    }
                }
            }
            is LiveEvent.ToolCall -> {
                FloatingOrb.activate(app)
                lastActivity = SystemClock.elapsedRealtime()
                VoiceIndicator.onProcessing()
                tools.handle(event.calls)
            }
            is LiveEvent.ToolCancelled -> tools.cancel(event.ids)
            is LiveEvent.ResumeHandle -> resumeHandle = event.handle
            LiveEvent.GoAway -> if (wanted) {
                Diag.i("socket: server asked to reconnect")
                client.close()
                connect()
            }
        }
    }

    override fun onClosed(problem: String?) {
        mic.stop()
        if (!wanted) return
        val nextRetry = synchronized(turnLock) {
            if (retries < MAX_RETRIES) ++retries else -1
        }
        if (nextRetry > 0) {
            Diag.w("socket: lost, retry $nextRetry of $MAX_RETRIES")
            scope.launch {
                delay(1_000L * nextRetry)
                if (wanted) connect()
            }
        } else {
            Diag.w("socket: giving up after $MAX_RETRIES retries")
            stop("connection lost")
            _state.update { it.copy(problem = problem ?: "Lost the connection to Gemini.") }
        }
    }

    private fun onMicChunk(raw: ByteArray) {
        val pcm = talkOver.clean(raw)
        // Without headphones the mic would hear the reply and answer itself.
        val echoRisk = settings.halfDuplex && !headphonesConnected(app)
        if (echoRisk && speaker.isAudible()) {
            val voice = if (settings.talkOver) talkOver.duringReply(raw, pcm) else null
            if (voice == null) {
                mutedChunks++
                return
            }
            interrupt("you talked over it")
            voice.forEach(client::sendAudio)
            return
        }
        val level = pcmLevel(pcm)
        val amp = loudness(level)
        _level.value = amp
        if (level > loudest) loudest = level
        if (level >= VOICE_LEVEL) {
            lastActivity = SystemClock.elapsedRealtime()
            if (!replyStarted) voiceAt = lastActivity
        }
        if (_state.value.phase == Phase.Listening) {
            VoiceIndicator.updateAmplitude(amp)
        }
        client.sendAudio(pcm)
    }

    private fun logTurnEnd() {
        Diag.i(
            "turn: done, mic muted ${mutedChunks * 100} ms for echo (peak ${talkOver.rawPeak}, after canceller " +
                "${talkOver.cleanPeak}, talk-over from ${talkOver.readyAfterMs} ms), speaker gaps ${speaker.takeGaps()}, " +
                "loudest mic level $loudest",
        )
        resetTurnLog()
    }

    private fun resetTurnLog() {
        speaker.takeGaps()
        mutedChunks = 0
        loudest = 0
        talkOver.reset()
        lastUserTurnText.clear()
        clearRetainedReplyAudio()

        heardUser = false
        voiceAt = 0
        replyStarted = false
    }

    private data class RetainedReplyAudio(
        val chunks: List<BufferedReplyAudio>,
        val overflowed: Boolean,
    )

    private fun retainReplyAudio(pcm: ByteArray, sampleRate: Int) {
        synchronized(bufferedReplyAudioLock) {
            if (bufferedReplyAudioOverflowed) return
            if (!LiveResponseVoicePolicy.canRetainAudio(bufferedReplyAudioBytes, pcm.size)) {
                bufferedReplyAudio.clear()
                bufferedReplyAudioBytes = 0
                bufferedReplyAudioOverflowed = true
                Diag.w("reply: retained Gemini audio fallback exceeded its memory budget")
                return
            }
            bufferedReplyAudio += BufferedReplyAudio(pcm.copyOf(), sampleRate)
            bufferedReplyAudioBytes += pcm.size
        }
    }

    private fun takeRetainedReplyAudio(): RetainedReplyAudio = synchronized(bufferedReplyAudioLock) {
        val result = RetainedReplyAudio(bufferedReplyAudio.toList(), bufferedReplyAudioOverflowed)
        bufferedReplyAudio.clear()
        bufferedReplyAudioBytes = 0
        bufferedReplyAudioOverflowed = false
        result
    }

    private fun clearRetainedReplyAudio() {
        synchronized(bufferedReplyAudioLock) {
            bufferedReplyAudio.clear()
            bufferedReplyAudioBytes = 0
            bufferedReplyAudioOverflowed = false
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        const val IDLE_MS = 50_000L
        private const val LOUD_LEVEL = 3_000f // a clear voice near the phone
        private const val WATCH_MS = 3_000L

        @Volatile private var instance: VoiceSession? = null

        fun get(context: Context): VoiceSession = instance ?: synchronized(this) {
            instance ?: VoiceSession(context.applicationContext as Application).also { instance = it }
        }
    }
}
