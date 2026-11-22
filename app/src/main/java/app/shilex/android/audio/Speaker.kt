package app.shilex.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.shilex.android.Diag
import app.shilex.android.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Explicit preview states distinguishing loading model weights vs active audio playback. */
enum class VoicePreviewState {
    Idle,
    Loading,
    Playing,
}

data class PreviewPlayback(
    val voiceId: String? = null,
    val state: VoicePreviewState = VoicePreviewState.Idle,
    val language: PreviewLanguage = PreviewLanguage.HINDI,
)

/**
 * Unified Audio Output & Speech Engine for JARVIS.
 * Handles both streaming raw PCM chunks (Gemini Live AudioTrack) and native
 * Android TextToSpeech synthesis with full male/female voice selection,
 * volume & audio focus management, and echo cancellation integration.
 */
class Speaker(private val context: Context? = null) {
    private val appContext = context?.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- PCM AudioTrack Engine (Gemini Live & Local PCM) ---
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "speaker") }
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate = 0

    // A 16 kHz copy of everything written to the track, for the echo canceller (TalkOver).
    private val ring = ShortArray(RING)
    private var ringWritten = 0L // samples since the track was created or flushed
    private var resamplePos = 0.0

    // Bumped by flush(); queued writes from before it are dropped.
    @Volatile private var generation = 0
    @Volatile private var audibleUntil = 0L

    // A reply runs from its first chunk to endReply() or flush().
    @Volatile private var replyOpen = false
    @Volatile private var gaps = 0
    private var underrunsSeen = 0 // speaker thread only

    // --- TextToSpeech Engine (Android System + Bundled Offline Neural Hindi) ---
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsInitInProgress = false
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var ttsGeneration = 0L
    private val ttsStateLock = Any()

    private val offlineHindiEngine: OfflineHindiTtsEngine? = appContext?.let { OfflineHindiTtsEngine(it) }
    private val offlineTtsExecutor = Executors.newSingleThreadExecutor { Thread(it, "offline-tts") }
    private val pendingOfflineUtterances = AtomicInteger(0)

    private val _availableVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceInfo>> = _availableVoices.asStateFlow()

    private val _hindiCatalog = MutableStateFlow(HindiVoiceCatalog())
    val hindiCatalog: StateFlow<HindiVoiceCatalog> = _hindiCatalog.asStateFlow()

    private val _engineDiagnostics = MutableStateFlow(TtsEngineDiagnostics())
    val engineDiagnostics: StateFlow<TtsEngineDiagnostics> = _engineDiagnostics.asStateFlow()

    private val _isTtsReadyState = MutableStateFlow(false)
    val isTtsReadyState: StateFlow<Boolean> = _isTtsReadyState.asStateFlow()

    private val _ttsError = MutableStateFlow<String?>(null)
    val ttsError: StateFlow<String?> = _ttsError.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _speechAmplitude = MutableStateFlow(0f)
    val speechAmplitude: StateFlow<Float> = _speechAmplitude.asStateFlow()

    private val _previewPlayback = MutableStateFlow(PreviewPlayback())
    val previewPlayback: StateFlow<PreviewPlayback> = _previewPlayback.asStateFlow()

    private val _previewingVoiceId = MutableStateFlow<String?>(null)
    val previewingVoiceId: StateFlow<String?> = _previewingVoiceId.asStateFlow()

    private val _isMediaMuted = MutableStateFlow(false)
    val isMediaMuted: StateFlow<Boolean> = _isMediaMuted.asStateFlow()

    private data class QueuedUtterance(
        val generation: Long,
        val text: String,
        val queueMode: Int,
        val utteranceId: String,
        val onComplete: (() -> Unit)?,
        val onInterrupted: (() -> Unit)?,
    )

    private data class UtteranceEntry(
        val generation: Long,
        val onComplete: (() -> Unit)?,
        val onInterrupted: (() -> Unit)?,
    )

    private val speechQueue = mutableListOf<QueuedUtterance>()
    private val initCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val utteranceCallbacks = ConcurrentHashMap<String, UtteranceEntry>()

    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var hasAudioFocus = false
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (TtsPlaybackPolicy.shouldFlushOnAudioFocusChange(focusChange)) {
            Diag.i("speaker: audio focus lost ($focusChange); flushing audio output")
            mainHandler.post { flush() }
        }
    }

    // Duplicate utterance guard
    private var lastSpokenText: String = ""
    private var lastSpokenTimeMs: Long = 0L

    init {
        if (appContext != null) {
            refreshAudioOutputState()
            discoverVoices()
            initTts()
        }
    }

    // ==========================================
    // 1. TextToSpeech Engine Lifecycle & Control
    // ==========================================

    fun refreshAudioOutputState(): Boolean {
        val ctx = appContext ?: return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val volume = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(1)
        val muted = runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
        val zeroOrMuted = TtsPlaybackPolicy.isMediaMutedOrZeroVolume(volume, muted)
        _isMediaMuted.value = zeroOrMuted
        return zeroOrMuted
    }

    fun isTtsReady(): Boolean = ttsReady || BundledHindiVoices.availableForRuntime(appContext).isNotEmpty()

    /** Initializes the TTS engine asynchronously if not already ready. */
    fun initTts(onReady: ((Boolean) -> Unit)? = null) {
        val ctx = appContext ?: run {
            onReady?.invoke(false)
            return
        }
        val hasBundled = BundledHindiVoices.availableForRuntime(ctx).isNotEmpty()
        if (hasBundled) {
            _isTtsReadyState.value = true
            _ttsError.value = null
        }
        if (ttsReady) {
            onReady?.invoke(true)
            return
        }
        synchronized(initCallbacks) {
            if (onReady != null) initCallbacks += onReady
            if (ttsInitInProgress) return
            ttsInitInProgress = true
        }
        mainHandler.post {
            try {
                runCatching { tts?.shutdown() }
                tts = null
                var created: TextToSpeech? = null
                created = TextToSpeech(ctx) { status ->
                    val completeInit = complete@{
                        val callbacks = synchronized(initCallbacks) {
                            ttsInitInProgress = false
                            initCallbacks.toList().also { initCallbacks.clear() }
                        }
                        val engine = created
                        if (status == TextToSpeech.SUCCESS && engine != null) {
                            tts = engine
                            ttsReady = true
                            _isTtsReadyState.value = true
                            _ttsError.value = null
                            configureTtsEngine()
                            drainSpeechQueue()
                            callbacks.forEach { it(true) }
                        } else {
                            runCatching { engine?.shutdown() }
                            tts = null
                            ttsReady = false
                            discoverVoices()
                            val bundledOk = BundledHindiVoices.availableForRuntime(ctx).isNotEmpty()
                            _isTtsReadyState.value = bundledOk
                            Diag.w("tts: system init failed with status $status (bundledAvailable=$bundledOk)")
                            if (bundledOk) {
                                _ttsError.value = null
                                drainSpeechQueue()
                                callbacks.forEach { it(true) }
                            } else {
                                _ttsError.value = "Voice playback is unavailable on this phone."
                                drainSpeechQueueOnError()
                                callbacks.forEach { it(false) }
                            }
                        }
                    }
                    if (created == null) mainHandler.post { completeInit() } else completeInit()
                }
            } catch (e: Exception) {
                val callbacks = synchronized(initCallbacks) {
                    ttsInitInProgress = false
                    initCallbacks.toList().also { initCallbacks.clear() }
                }
                tts = null
                ttsReady = false
                discoverVoices()
                val bundledOk = BundledHindiVoices.availableForRuntime(ctx).isNotEmpty()
                _isTtsReadyState.value = bundledOk
                Diag.w("tts: start exception: ${e.message} (bundledAvailable=$bundledOk)")
                if (bundledOk) {
                    _ttsError.value = null
                    drainSpeechQueue()
                    callbacks.forEach { it(true) }
                } else {
                    _ttsError.value = "Voice playback could not start on this phone."
                    drainSpeechQueueOnError()
                    callbacks.forEach { it(false) }
                }
            }
        }
    }

    private fun configureTtsEngine() {
        val engine = tts ?: return
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val entry = utteranceId?.let { utteranceCallbacks[it] }
                    val started = synchronized(ttsStateLock) {
                        if (utteranceId == null || entry == null || entry.generation != ttsGeneration) {
                            false
                        } else {
                            // QUEUE_ADD utterances become active when Android actually starts them.
                            activeUtteranceId = utteranceId
                            _isSpeaking.value = true
                            _speechAmplitude.value = 0.45f
                            if (utteranceId?.startsWith("preview_") == true) {
                                _previewingVoiceId.value?.let { previewId ->
                                    _previewPlayback.value = PreviewPlayback(
                                        voiceId = previewId,
                                        state = VoicePreviewState.Playing,
                                        language = _previewPlayback.value.language,
                                    )
                                }
                            }
                            true
                        }
                    }
                    if (started) {
                        mainHandler.removeCallbacksAndMessages(PREVIEW_TIMEOUT_TOKEN)
                        Diag.i("tts: playback started for utterance $utteranceId")
                        AudioPlaybackState.onLocalTtsStarted()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    handleUtteranceFinished(utteranceId, completedNaturally = true)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == null || utteranceId == activeUtteranceId) {
                        _ttsError.value = "Voice playback could not start on this phone."
                    }
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Diag.w("tts: error $errorCode on utterance $utteranceId")
                    if (utteranceId == null || utteranceId == activeUtteranceId) {
                        _ttsError.value = "Voice playback failed on this phone."
                    }
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                }
            })

            discoverVoices()
            applySavedVoiceAndSettings()
        } catch (e: Exception) {
            Diag.w("tts: error during configuration: ${e.message}")
        }
    }

    private fun handleUtteranceFinished(utteranceId: String?, completedNaturally: Boolean) {
        if (utteranceId == null || utteranceId.startsWith("preview_")) {
            mainHandler.removeCallbacksAndMessages(PREVIEW_TIMEOUT_TOKEN)
        }
        val entry = utteranceId?.let { utteranceCallbacks.remove(it) }
        val isStale = synchronized(ttsStateLock) {
            entry != null && entry.generation != ttsGeneration
        }
        if (!isStale && entry != null) {
            if (completedNaturally) {
                entry.onComplete?.invoke()
            } else {
                entry.onInterrupted?.invoke()
            }
        } else if (isStale) {
            entry?.onInterrupted?.invoke()
        }
        val finishState: Boolean? = synchronized(ttsStateLock) {
            val wasPreview = _previewingVoiceId.value != null ||
                _previewPlayback.value.state != VoicePreviewState.Idle ||
                (utteranceId != null && utteranceId.startsWith("preview_"))
            if (!TtsPlaybackPolicy.isCurrentCallback(
                    callbackUtteranceId = utteranceId,
                    activeUtteranceId = activeUtteranceId,
                    callbackGeneration = entry?.generation,
                    activeGeneration = ttsGeneration,
                )
            ) {
                return@synchronized null
            }
            activeUtteranceId = null
            _isSpeaking.value = false
            _speechAmplitude.value = 0f
            if (wasPreview) {
                _previewPlayback.value = PreviewPlayback()
                _previewingVoiceId.value = null
            }
            wasPreview
        }
        if (finishState == true) {
            applySavedVoiceAndSettings()
        }
        if (finishState != null) {
            Diag.i("tts: playback ${if (completedNaturally) "completed" else "interrupted"} for utterance $utteranceId")
            AudioPlaybackState.onLocalTtsEnded()
            if (!replyOpen && !hasPendingOrActiveSpeech()) {
                writer.execute { resetTrackSafely() }
            }
            abandonAudioFocus()
        }
    }

    /**
     * Discovers genuine Hindi (India) `hi-IN` voices on the active TTS engine and checks
     * whether any verified offline Hindi neural models are bundled and integrated.
     */
    fun discoverVoices() {
        val bundledList = BundledHindiVoices.availableForRuntime(appContext)
        val engine = tts
        if (engine == null) {
            val catalog = VoiceClassifier.buildHindiCatalog(emptyList(), bundledList)
            _hindiCatalog.value = catalog
            _availableVoices.value = catalog.femaleVoices + catalog.maleVoices
            if (bundledList.isNotEmpty()) {
                _isTtsReadyState.value = true
            }
            _engineDiagnostics.value = TtsEngineDiagnostics(
                totalHindiVoicesCount = catalog.totalHindiVoicesCount,
                installedHindiVoicesCount = catalog.installedHindiVoicesCount,
                bundledHindiVoicesCount = bundledList.size,
            )
            return
        }

        val rawVoices = runCatching { engine.voices }.getOrNull().orEmpty()
        val activePkg = runCatching { engine.defaultEngine }.getOrNull()
        val engines = runCatching { engine.engines }.getOrNull().orEmpty()
        val googleInstalled = engines.any { it.name == VoiceClassifier.GOOGLE_TTS_PACKAGE }
        val hiAvailability = runCatching { engine.isLanguageAvailable(Locale("hi", "IN")) }.getOrNull()

        val mapped = rawVoices.map { v ->
            RawTtsVoice(
                id = v.name,
                locale = v.locale,
                isNetworkRequired = v.isNetworkConnectionRequired,
                qualityScore = v.quality,
                features = v.features,
            )
        }

        val catalog = VoiceClassifier.buildHindiCatalog(mapped, bundledList)
        _hindiCatalog.value = catalog
        _availableVoices.value = catalog.femaleVoices + catalog.maleVoices
        if (bundledList.isNotEmpty()) {
            _isTtsReadyState.value = true
        }
        _engineDiagnostics.value = TtsEngineDiagnostics(
            activeEnginePackage = activePkg,
            isGoogleSpeechServicesActive = activePkg == VoiceClassifier.GOOGLE_TTS_PACKAGE,
            isGoogleSpeechServicesInstalled = googleInstalled,
            hindiLanguageAvailability = hiAvailability,
            totalEngineVoicesCount = rawVoices.size,
            totalHindiVoicesCount = catalog.totalHindiVoicesCount,
            installedHindiVoicesCount = catalog.installedHindiVoicesCount,
            bundledHindiVoicesCount = bundledList.size,
        )
    }

    /** Clears transient voice errors and retries/refreshes voice playback initialization. */
    fun retryTts(onComplete: ((Boolean) -> Unit)? = null) {
        _ttsError.value = null
        discoverVoices()
        if (ttsReady && tts != null) {
            applySavedVoiceAndSettings()
            onComplete?.invoke(true)
        } else {
            initTts(onComplete)
        }
    }

    /**
     * Applies the saved voice ID, locale, speech rate, and pitch from Settings
     * directly to the active TTS engine.
     */
    fun applySavedVoiceAndSettings() {
        val ctx = appContext ?: return
        val engine = tts ?: return
        if (!ttsReady) return

        val settings = Settings(ctx)
        val targetVoiceId = settings.ttsVoiceId

        // If a bundled offline voice is selected, it takes precedence and Android TTS voice doesn't override it
        if (targetVoiceId != null && BundledHindiVoices.find(targetVoiceId) != null) {
            return
        }

        // 1. Voice profile selection (prefer genuine installed offline Hindi hi-IN voices)
        val allVoices = runCatching { engine.voices }.getOrNull()
        if (!allVoices.isNullOrEmpty()) {
            val installedVoices = allVoices.filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }.ifEmpty { allVoices.toList() }

            val matching = if (targetVoiceId != null) {
                installedVoices.firstOrNull { it.name == targetVoiceId }
                    ?: allVoices.firstOrNull { it.name == targetVoiceId }
            } else {
                null
            }

            if (matching != null) {
                runCatching { engine.voice = matching }
            } else {
                // Fallback: prefer installed offline hi-IN voice matching selected category, then any installed hi-IN
                val catalog = _hindiCatalog.value
                val preferredCatalogId = if (settings.ttsGenderFilter == Settings.GENDER_MALE) {
                    catalog.maleVoices.firstOrNull { it.isInstalled }?.id
                        ?: catalog.femaleVoices.firstOrNull { it.isInstalled }?.id
                } else {
                    catalog.femaleVoices.firstOrNull { it.isInstalled }?.id
                        ?: catalog.maleVoices.firstOrNull { it.isInstalled }?.id
                }
                val defaultFallback = preferredCatalogId?.let { id ->
                    installedVoices.firstOrNull { it.name == id }
                } ?: installedVoices
                    .filter { VoiceClassifier.isHindiIndiaLocale(it.locale) }
                    .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality })
                    .firstOrNull()
                    ?: installedVoices.firstOrNull { it.locale.language == "en" && it.locale.country == "IN" }
                    ?: installedVoices.firstOrNull { it.locale == Locale.getDefault() }
                    ?: runCatching { engine.defaultVoice }.getOrNull()
                defaultFallback?.let { v -> runCatching { engine.voice = v } }
            }
        } else {
            val hiLocale = Locale("hi", "IN")
            val hiResult = runCatching { engine.setLanguage(hiLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (hiResult == TextToSpeech.LANG_MISSING_DATA || hiResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallbackResult = runCatching { engine.setLanguage(Locale.getDefault()) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    _ttsError.value = "Hindi voices are unavailable on this phone right now."
                }
            }
        }

        // 2. Speech rate and pitch
        runCatching { engine.setSpeechRate(settings.ttsSpeechRate) }
        runCatching { engine.setPitch(settings.ttsPitch) }
    }

    /**
     * Speaks the given [text] reply aloud using the active TTS voice.
     * Prevents duplicate utterances and ignores internal tool calls.
     */
    fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        onInterrupted: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val ctx = appContext
        val settings = ctx?.let(::Settings)
        if (settings != null && !settings.speakResponses) {
            onComplete?.invoke()
            return
        }

        val cleaned = cleanSpokenText(text)
        if (cleaned.isBlank()) {
            onComplete?.invoke()
            return
        }

        // Deduplication guard: do not repeat the exact same sentence within 1.2 seconds
        val now = SystemClock.elapsedRealtime()
        if (cleaned == lastSpokenText && now - lastSpokenTimeMs < DUPLICATE_WINDOW_MS) {
            onComplete?.invoke()
            return
        }
        lastSpokenText = cleaned
        lastSpokenTimeMs = now

        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            interruptActiveAudioForNewSpeech()
        }
        refreshAudioOutputState()
        // Resolve and strictly validate saved voice so all speech outputs use the exact selected voice.
        val effectiveVoiceId = VoiceClassifier.resolveEffectiveVoiceId(
            savedVoiceId = settings?.ttsVoiceId,
            catalog = _hindiCatalog.value,
            preferMale = settings?.ttsGenderFilter == Settings.GENDER_MALE,
        )
        val bundledVoice = BundledHindiVoices.find(effectiveVoiceId)
        val offlineEngine = offlineHindiEngine
        if (bundledVoice != null) {
            val validation = VoiceClassifier.validateBundledVoice(bundledVoice)
            if (!validation.isValid) {
                _ttsError.value = validation.reason
                onInterrupted?.invoke()
                return
            }
            if (offlineEngine == null || !offlineEngine.isVoiceAvailable(bundledVoice)) {
                _ttsError.value = "${bundledVoice.displayName} files are unavailable on this device."
                onInterrupted?.invoke()
                return
            }
            val utteranceId = "reply_${UUID.randomUUID()}"
            val gen = synchronized(ttsStateLock) {
                val g = ttsGeneration
                utteranceCallbacks[utteranceId] = UtteranceEntry(g, onComplete, onInterrupted)
                _ttsError.value = null
                _previewingVoiceId.value = null
                if (queueMode == TextToSpeech.QUEUE_FLUSH || activeUtteranceId == null) {
                    activeUtteranceId = utteranceId
                    _isSpeaking.value = false
                    _speechAmplitude.value = 0f
                }
                g
            }
            speakBundledOffline(
                voice = bundledVoice,
                text = cleaned,
                speed = settings?.ttsSpeechRate ?: 1.0f,
                utteranceId = utteranceId,
                gen = gen,
                isPreview = false,
            )
            return
        }

        val utteranceId = "reply_${UUID.randomUUID()}"
        val gen = synchronized(ttsStateLock) { ttsGeneration }
        utteranceCallbacks[utteranceId] = UtteranceEntry(gen, onComplete, onInterrupted)

        if (!ttsReady) {
            synchronized(speechQueue) {
                if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                    speechQueue.forEach { it.onInterrupted?.invoke() }
                    speechQueue.clear()
                }
                speechQueue += QueuedUtterance(gen, cleaned, queueMode, utteranceId, onComplete, onInterrupted)
            }
            initTts()
            return
        }

        val engine = tts
        if (engine == null) {
            _ttsError.value = "Voice playback is unavailable on this device."
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            return
        }

        applySavedVoiceAndSettings()
        if (!requestAudioFocus()) {
            _ttsError.value = "Audio is in use by another app; voice playback was stopped."
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            synchronized(ttsStateLock) {
                if (activeUtteranceId == utteranceId) activeUtteranceId = null
            }
            return
        }
        _ttsError.value = null
        synchronized(ttsStateLock) {
            if (queueMode == TextToSpeech.QUEUE_FLUSH || activeUtteranceId == null) {
                activeUtteranceId = utteranceId
                _isSpeaking.value = false
                _speechAmplitude.value = 0f
            }
        }

        val result = runCatching { engine.speak(cleaned, queueMode, null, utteranceId) }
            .getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            Diag.w("tts: speak failed with code $result")
            synchronized(ttsStateLock) {
                if (activeUtteranceId == utteranceId) activeUtteranceId = null
                _isSpeaking.value = false
                _speechAmplitude.value = 0f
            }
            _ttsError.value = "Voice playback could not speak this reply."
            abandonAudioFocus()
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
        }
    }

    /**
     * Speaks a short natural Hindi or English preview sentence in the target voice profile without
     * changing the permanent selected assistant voice setting. Cancels any active preview immediately.
     */
    fun previewVoice(
        voiceId: String,
        sampleText: String? = null,
        language: PreviewLanguage = PreviewLanguage.HINDI,
        speechRateOverride: Float? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        // Preview owns the shared output path while active. Flush assistant speech, queued
        // utterances and any previous preview before loading the requested identity.
        flush()
        refreshAudioOutputState()

        val previewSentence = sampleText?.ifBlank { null }
            ?: VoiceClassifier.previewSampleText(language)
        val effectiveRate = (speechRateOverride ?: appContext?.let { Settings(it).ttsSpeechRate } ?: 1.0f)
            .coerceIn(0.5f, 2.0f)

        val bundledVoice = BundledHindiVoices.find(voiceId)
        val offlineEngine = offlineHindiEngine
        if (bundledVoice != null) {
            val validation = VoiceClassifier.validateBundledVoice(bundledVoice)
            if (!validation.isValid) {
                _ttsError.value = validation.reason
                applySavedVoiceAndSettings()
                onComplete?.invoke()
                return
            }
            if (offlineEngine == null || !offlineEngine.isVoiceAvailable(bundledVoice)) {
                _ttsError.value = "This Hindi voice is unavailable right now."
                applySavedVoiceAndSettings()
                onComplete?.invoke()
                return
            }
            val utteranceId = "preview_${UUID.randomUUID()}"
            val gen = synchronized(ttsStateLock) {
                val g = ttsGeneration
                utteranceCallbacks[utteranceId] = UtteranceEntry(g, onComplete, onComplete)
                _ttsError.value = null
                activeUtteranceId = utteranceId
                _previewPlayback.value = PreviewPlayback(voiceId, VoicePreviewState.Loading, language)
                _previewingVoiceId.value = voiceId
                _isSpeaking.value = false
                _speechAmplitude.value = 0f
                g
            }

            speakBundledOffline(
                voice = bundledVoice,
                text = previewSentence,
                speed = effectiveRate,
                utteranceId = utteranceId,
                gen = gen,
                isPreview = true,
                previewLanguage = language,
            )
            return
        }

        val engine = tts
        if (!ttsReady || engine == null) {
            val pendingId = "preview_${UUID.randomUUID()}"
            val requestGen = synchronized(ttsStateLock) {
                val g = ttsGeneration
                utteranceCallbacks[pendingId] = UtteranceEntry(g, onComplete, onComplete)
                _ttsError.value = null
                activeUtteranceId = pendingId
                _previewPlayback.value = PreviewPlayback(voiceId, VoicePreviewState.Loading, language)
                _previewingVoiceId.value = voiceId
                _isSpeaking.value = false
                _speechAmplitude.value = 0f
                g
            }
            initTts { ok ->
                val stillCurrent = synchronized(ttsStateLock) { requestGen == ttsGeneration }
                if (!stillCurrent) {
                    utteranceCallbacks.remove(pendingId)?.onInterrupted?.invoke()
                    return@initTts
                }
                if (ok) {
                    startSystemVoicePreview(voiceId, previewSentence, pendingId, requestGen, effectiveRate)
                } else {
                    synchronized(ttsStateLock) {
                        if (activeUtteranceId == pendingId) {
                            activeUtteranceId = null
                            _previewPlayback.value = PreviewPlayback()
                            _previewingVoiceId.value = null
                            _isSpeaking.value = false
                            _speechAmplitude.value = 0f
                        }
                    }
                    _ttsError.value = "Voice playback is unavailable right now."
                    utteranceCallbacks.remove(pendingId)?.onInterrupted?.invoke()
                    applySavedVoiceAndSettings()
                }
            }
            return
        }

        val utteranceId = "preview_${UUID.randomUUID()}"
        val gen = synchronized(ttsStateLock) {
            val g = ttsGeneration
            utteranceCallbacks[utteranceId] = UtteranceEntry(g, onComplete, onComplete)
            _ttsError.value = null
            activeUtteranceId = utteranceId
            _previewPlayback.value = PreviewPlayback(voiceId, VoicePreviewState.Loading, language)
            _previewingVoiceId.value = voiceId
            _isSpeaking.value = false
            _speechAmplitude.value = 0f
            g
        }
        startSystemVoicePreview(voiceId, previewSentence, utteranceId, gen, effectiveRate)
    }

    private fun startSystemVoicePreview(
        voiceId: String,
        sampleText: String?,
        utteranceId: String,
        expectedGen: Long,
        speechRate: Float,
    ) {
        val engine = tts
        val stillCurrent = synchronized(ttsStateLock) { expectedGen == ttsGeneration }
        if (!stillCurrent) {
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            return
        }
        if (!ttsReady || engine == null) {
            synchronized(ttsStateLock) {
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                    _previewPlayback.value = PreviewPlayback()
                    _previewingVoiceId.value = null
                    _isSpeaking.value = false
                    _speechAmplitude.value = 0f
                }
            }
            _ttsError.value = "Voice playback is unavailable right now."
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            applySavedVoiceAndSettings()
            return
        }

        val targetVoice = runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == voiceId }
        if (targetVoice == null) {
            synchronized(ttsStateLock) {
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                    _previewPlayback.value = PreviewPlayback()
                    _previewingVoiceId.value = null
                    _isSpeaking.value = false
                    _speechAmplitude.value = 0f
                }
            }
            _ttsError.value = "This Hindi voice is unavailable right now."
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            applySavedVoiceAndSettings()
            return
        }

        if (targetVoice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
            synchronized(ttsStateLock) {
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                    _previewPlayback.value = PreviewPlayback()
                    _previewingVoiceId.value = null
                    _isSpeaking.value = false
                    _speechAmplitude.value = 0f
                }
            }
            _ttsError.value = "This voice pack is not downloaded on your phone yet. Choose another voice or install voice data in Android TTS settings."
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            applySavedVoiceAndSettings()
            return
        }

        val previewLocale = targetVoice.locale ?: Locale("hi", "IN")
        if (!VoiceClassifier.isHindiIndiaLocale(previewLocale)) {
            synchronized(ttsStateLock) {
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                    _previewPlayback.value = PreviewPlayback()
                    _previewingVoiceId.value = null
                    _isSpeaking.value = false
                    _speechAmplitude.value = 0f
                }
            }
            _ttsError.value = "Only Hindi (India) voices are supported."
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            applySavedVoiceAndSettings()
            return
        }
        val textToSpeak = sampleText ?: VoiceClassifier.sampleTextForLocale(previewLocale)
        val effectivePitch = (appContext?.let { Settings(it).ttsPitch } ?: 1.0f).coerceIn(0.5f, 1.5f)

        runCatching { engine.setSpeechRate(speechRate) }
        runCatching { engine.setPitch(effectivePitch) }
        if (runCatching { engine.voice = targetVoice }.isFailure) {
            synchronized(ttsStateLock) {
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                    _previewPlayback.value = PreviewPlayback()
                    _previewingVoiceId.value = null
                    _isSpeaking.value = false
                    _speechAmplitude.value = 0f
                }
            }
            _ttsError.value = "Could not load this Hindi voice."
            applySavedVoiceAndSettings()
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            return
        }

        val activated = synchronized(ttsStateLock) {
            if (expectedGen != ttsGeneration) {
                false
            } else {
                _ttsError.value = null
                activeUtteranceId = utteranceId
                _previewPlayback.value = _previewPlayback.value.copy(voiceId = voiceId, state = VoicePreviewState.Loading)
                _previewingVoiceId.value = voiceId
                _isSpeaking.value = false
                _speechAmplitude.value = 0f
                true
            }
        }
        if (!activated) {
            applySavedVoiceAndSettings()
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            return
        }
        if (!requestAudioFocus()) {
            _ttsError.value = "Audio is in use by another app; preview could not start."
            handleUtteranceFinished(utteranceId, completedNaturally = false)
            return
        }

        mainHandler.removeCallbacksAndMessages(PREVIEW_TIMEOUT_TOKEN)
        mainHandler.postDelayed(
            {
                val stalled = synchronized(ttsStateLock) {
                    expectedGen == ttsGeneration &&
                        activeUtteranceId == utteranceId &&
                        _previewPlayback.value.state == VoicePreviewState.Loading
                }
                if (stalled) {
                    Diag.w("tts: preview timed out before playback started for $voiceId")
                    runCatching { engine.stop() }
                    _ttsError.value = if (targetVoice.isNetworkConnectionRequired) {
                        "This online voice did not respond. Check internet connectivity or pick an offline voice."
                    } else {
                        "Voice preview did not start on this device. Try another voice."
                    }
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                }
            },
            PREVIEW_TIMEOUT_TOKEN,
            PREVIEW_START_TIMEOUT_MS,
        )

        val res = runCatching { engine.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId) }
            .getOrDefault(TextToSpeech.ERROR)
        if (res != TextToSpeech.SUCCESS) {
            mainHandler.removeCallbacksAndMessages(PREVIEW_TIMEOUT_TOKEN)
            Diag.w("tts: preview failed with code $res")
            synchronized(ttsStateLock) {
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                    _previewPlayback.value = PreviewPlayback()
                    _previewingVoiceId.value = null
                    _isSpeaking.value = false
                    _speechAmplitude.value = 0f
                }
            }
            _ttsError.value = "Could not play voice preview."
            abandonAudioFocus()
            utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
            applySavedVoiceAndSettings()
        }
    }

    private fun speakBundledOffline(
        voice: BundledHindiVoice,
        text: String,
        speed: Float,
        utteranceId: String,
        gen: Long,
        isPreview: Boolean,
        previewLanguage: PreviewLanguage = PreviewLanguage.HINDI,
    ) {
        val offlineEngine = offlineHindiEngine ?: run {
            _ttsError.value = "Offline voice engine is unavailable."
            handleUtteranceFinished(utteranceId, completedNaturally = false)
            return
        }
        pendingOfflineUtterances.incrementAndGet()
        offlineTtsExecutor.execute {
            try {
                val activeValid = synchronized(ttsStateLock) {
                    if (gen != ttsGeneration) {
                        false
                    } else {
                        _ttsError.value = null
                        activeUtteranceId = utteranceId
                        if (isPreview) {
                            _previewPlayback.value = PreviewPlayback(voice.id, VoicePreviewState.Loading, previewLanguage)
                            _previewingVoiceId.value = voice.id
                        }
                        true
                    }
                }
                if (!activeValid) {
                    utteranceCallbacks.remove(utteranceId)?.onInterrupted?.invoke()
                    return@execute
                }

                if (!requestAudioFocus()) {
                    synchronized(ttsStateLock) {
                        if (gen == ttsGeneration) {
                            _ttsError.value = "Audio is in use by another app; voice playback was stopped."
                        }
                    }
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                    return@execute
                }

                val playbackStartMs = AtomicLong(0L)
                var totalFrames = 0L
                var activeRate = voice.sampleRate
                val lastWriteDoneMs = AtomicLong(0L)
                val writeFailed = AtomicBoolean(false)
                val playbackStarted = AtomicBoolean(false)
                // Capture playbackHeadPosition baseline on the writer thread before draining PCM
                val startHeadFrames = AtomicLong(-1L)
                var trailingSilenceFrames = 0L

                val outcome = offlineEngine.synthesizeStreaming(
                    voice = voice,
                    text = text,
                    speed = speed,
                    isCancelled = { gen != ttsGeneration },
                    onChunk = { chunk ->
                        if (gen != ttsGeneration || writeFailed.get()) return@synthesizeStreaming
                        if (chunk.pcm16.isEmpty() || chunk.sampleRate <= 0) return@synthesizeStreaming
                        activeRate = chunk.sampleRate
                        val frames = chunk.pcm16.size / 2L
                        totalFrames += frames
                        val chunkDurationMs = frames * 1000L / activeRate

                        val pcmGen = generation
                        writer.execute {
                            try {
                                if (gen != ttsGeneration || pcmGen != generation) return@execute
                                val preparedTrack = trackFor(chunk.sampleRate)
                                if (preparedTrack == null) {
                                    writeFailed.set(true)
                                    return@execute
                                }
                                if (startHeadFrames.get() < 0L) {
                                    val pos = runCatching { preparedTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
                                    startHeadFrames.compareAndSet(-1L, pos)
                                }
                                if (!writePcmToTrack(chunk.pcm16, chunk.sampleRate)) {
                                    writeFailed.set(true)
                                } else {
                                    if (gen != ttsGeneration || pcmGen != generation) return@execute
                                    val nowMs = SystemClock.elapsedRealtime()
                                    lastWriteDoneMs.set(nowMs)
                                    audibleUntil = maxOf(audibleUntil, nowMs) + chunkDurationMs + 140L
                                    if (playbackStarted.compareAndSet(false, true)) {
                                        playbackStartMs.set(nowMs)
                                        val currentPlayback = synchronized(ttsStateLock) {
                                            if (gen == ttsGeneration && activeUtteranceId == utteranceId) {
                                                if (isPreview) {
                                                    _previewPlayback.value = PreviewPlayback(voice.id, VoicePreviewState.Playing, previewLanguage)
                                                    _previewingVoiceId.value = voice.id
                                                }
                                                _isSpeaking.value = true
                                                _speechAmplitude.value = chunk.peakAmplitude
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                        if (currentPlayback) {
                                            Diag.i("offline-tts: audio output started for ${voice.id}")
                                            AudioPlaybackState.onLocalTtsStarted()
                                        }
                                    } else if (gen == ttsGeneration) {
                                        _speechAmplitude.value = chunk.peakAmplitude
                                    }
                                }
                            } catch (t: Throwable) {
                                Diag.w("speaker: writer chunk error: ${t.message}")
                                writeFailed.set(true)
                            }
                        }
                    },
                )

                if (gen != ttsGeneration || outcome is OfflineHindiTtsEngine.SynthesisOutcome.Cancelled) {
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                    return@execute
                }

                if (outcome is OfflineHindiTtsEngine.SynthesisOutcome.Failed) {
                    Diag.w("offline-tts: synthesis failed for ${voice.id}: ${outcome.userMessage}")
                    synchronized(ttsStateLock) {
                        if (gen == ttsGeneration) {
                            _ttsError.value = outcome.userMessage
                            if (isPreview) {
                                _previewPlayback.value = PreviewPlayback()
                                _previewingVoiceId.value = null
                            }
                        }
                    }
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                    return@execute
                }

                Diag.i("offline-tts: generated $totalFrames PCM frames for ${voice.id}")

                if (totalFrames <= 0L) {
                    synchronized(ttsStateLock) {
                        if (gen == ttsGeneration) {
                            _ttsError.value = "${voice.displayName} produced no audio frames."
                            if (isPreview) {
                                _previewPlayback.value = PreviewPlayback()
                                _previewingVoiceId.value = null
                            }
                        }
                    }
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                    return@execute
                }

                // Append a brief comfort-silence tail so Android's HAL flushes the final spoken syllable cleanly
                if (gen == ttsGeneration && !writeFailed.get()) {
                    val silenceBytes = ByteArray(((activeRate / 16) * 2).coerceAtLeast(1024) and 1.inv())
                    trailingSilenceFrames = silenceBytes.size / 2L
                    val pcmGen = generation
                    runCatching {
                        writer.submit {
                            if (gen == ttsGeneration && pcmGen == generation) {
                                writePcmToTrack(silenceBytes, activeRate)
                            }
                        }.get()
                    }
                }
                if (writeFailed.get()) {
                    synchronized(ttsStateLock) {
                        if (gen == ttsGeneration) {
                            _ttsError.value = "Could not play audio on this phone's speaker."
                            if (isPreview) {
                                _previewPlayback.value = PreviewPlayback()
                                _previewingVoiceId.value = null
                            }
                        }
                    }
                    handleUtteranceFinished(utteranceId, completedNaturally = false)
                    return@execute
                }

                if (playbackStartMs.get() > 0L && totalFrames > 0L) {
                    val writeEndMs = lastWriteDoneMs.get()
                    val headBase = startHeadFrames.get().coerceAtLeast(0L)
                    val targetHeadFrames = headBase + totalFrames + (trailingSilenceFrames / 2L)
                    val minEndMs = maxOf(
                        playbackStartMs.get() + (totalFrames * 1000L / activeRate) + 120L,
                        if (writeEndMs > 0L) writeEndMs + 360L else 0L,
                    )
                    val maxWaitDeadlineMs = minEndMs + 1_200L
                    while (gen == ttsGeneration) {
                        val now = SystemClock.elapsedRealtime()
                        val currentTrack = track
                        val currentHead = runCatching {
                            if (currentTrack != null && currentTrack.state == AudioTrack.STATE_INITIALIZED) {
                                currentTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            } else {
                                targetHeadFrames
                            }
                        }.getOrDefault(targetHeadFrames)
                        val drainedByHead = currentHead >= targetHeadFrames
                        if ((drainedByHead && now >= playbackStartMs.get() + 180L) || now >= maxWaitDeadlineMs) {
                            break
                        }
                        try {
                            Thread.sleep(35L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }

                handleUtteranceFinished(utteranceId, completedNaturally = gen == ttsGeneration)
            } catch (t: Throwable) {
                Diag.w("offline-tts: unexpected executor error for ${voice.id}: ${t.message}")
                synchronized(ttsStateLock) {
                    if (gen == ttsGeneration) {
                        _ttsError.value = if (isPreview) "Could not play voice preview." else "Voice playback could not speak this reply."
                        if (isPreview) {
                            _previewPlayback.value = PreviewPlayback()
                            _previewingVoiceId.value = null
                        }
                    }
                }
                handleUtteranceFinished(utteranceId, completedNaturally = false)
            } finally {
                pendingOfflineUtterances.decrementAndGet()
            }
        }
    }

    fun stopPreview() {
        stopPreviewInternal(clearSavedVoice = true)
    }

    private fun stopPreviewInternal(clearSavedVoice: Boolean) {
        mainHandler.removeCallbacksAndMessages(PREVIEW_TIMEOUT_TOKEN)
        val wasPreviewing = synchronized(ttsStateLock) {
            val isPrev = _previewingVoiceId.value != null ||
                _previewPlayback.value.state != VoicePreviewState.Idle ||
                activeUtteranceId?.startsWith("preview_") == true
            if (isPrev) {
                ttsGeneration++
                activeUtteranceId = null
                _isSpeaking.value = false
                _speechAmplitude.value = 0f
                _previewPlayback.value = PreviewPlayback()
                _previewingVoiceId.value = null
            }
            isPrev
        }
        if (wasPreviewing) {
            runCatching { tts?.stop() }
            if (!replyOpen) {
                generation++
                audibleUntil = 0L
                writer.execute {
                    resetTrackSafely()
                    clearRing()
                }
            }
            val pendingPreviewIds = utteranceCallbacks.keys.filter { it.startsWith("preview_") }
            pendingPreviewIds.forEach { id -> utteranceCallbacks.remove(id)?.onInterrupted?.invoke() }
            AudioPlaybackState.onLocalTtsEnded()
            abandonAudioFocus()
            if (clearSavedVoice) {
                applySavedVoiceAndSettings()
            }
        }
    }

    private fun drainSpeechQueue() {
        val queue = synchronized(speechQueue) {
            val curGen = ttsGeneration
            val copy = speechQueue.filter { it.generation == curGen }
            val dropped = speechQueue.filter { it.generation != curGen }
            speechQueue.clear()
            dropped.forEach { it.onInterrupted?.invoke() }
            copy
        }
        for (item in queue) {
            if (ttsReady) {
                utteranceCallbacks.remove(item.utteranceId)
                speak(item.text, item.queueMode, item.onInterrupted, item.onComplete)
            } else {
                utteranceCallbacks.remove(item.utteranceId)
                item.onInterrupted?.invoke()
            }
        }
    }

    private fun drainSpeechQueueOnError() {
        val queue = synchronized(speechQueue) {
            val copy = speechQueue.toList()
            speechQueue.clear()
            copy
        }
        queue.forEach { it.onInterrupted?.invoke() }
    }

    private fun interruptActiveAudioForNewSpeech() {
        synchronized(ttsStateLock) {
            ttsGeneration++
            activeUtteranceId = null
            _previewPlayback.value = PreviewPlayback()
            _previewingVoiceId.value = null
            _isSpeaking.value = false
            _speechAmplitude.value = 0f
        }
        runCatching { tts?.stop() }
        if (!replyOpen) {
            generation++
            audibleUntil = 0L
            writer.execute {
                resetTrackSafely()
                clearRing()
            }
        }
        val flushed = utteranceCallbacks.entries.toList()
        utteranceCallbacks.clear()
        flushed.forEach { it.value.onInterrupted?.invoke() }
    }

    fun stopTts() {
        mainHandler.removeCallbacksAndMessages(PREVIEW_TIMEOUT_TOKEN)
        synchronized(ttsStateLock) {
            ttsGeneration++
            activeUtteranceId = null
            _isSpeaking.value = false
            _speechAmplitude.value = 0f
            _previewPlayback.value = PreviewPlayback()
            _previewingVoiceId.value = null
        }
        runCatching { tts?.stop() }
        if (!replyOpen) {
            generation++
            audibleUntil = 0L
            writer.execute {
                resetTrackSafely()
                clearRing()
            }
        }
        val queued = synchronized(speechQueue) {
            val copy = speechQueue.toList()
            speechQueue.clear()
            copy
        }
        queued.forEach { it.onInterrupted?.invoke() }
        val pendingIds = utteranceCallbacks.keys.toList()
        pendingIds.forEach { id -> utteranceCallbacks.remove(id)?.onInterrupted?.invoke() }
        AudioPlaybackState.onLocalTtsEnded()
        abandonAudioFocus()
    }

    // ==========================================
    // 2. Audio Focus Management
    // ==========================================

    private fun requestAudioFocus(): Boolean {
        val ctx = appContext ?: return true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build().also { audioFocusRequest = it }
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus = granted
        return granted
    }

    private fun abandonAudioFocus() {
        val shouldAbandon = synchronized(ttsStateLock) {
            if (!hasAudioFocus || replyOpen || _isSpeaking.value || activeUtteranceId != null || pendingOfflineUtterances.get() > 0) {
                false
            } else {
                hasAudioFocus = false
                true
            }
        }
        if (!shouldAbandon) return
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
    }

    // ==========================================
    // 3. Streaming PCM Engine (AudioTrack)
    // ==========================================

    fun play(pcm: ByteArray, sampleRate: Int) {
        val gen = generation
        val first = !replyOpen
        if (first && !requestAudioFocus()) {
            Diag.w("speaker: live audio dropped because audio focus was denied")
            return
        }
        replyOpen = true
        val now = SystemClock.elapsedRealtime()
        val durationMs = pcm.size / 2 * 1000L / sampleRate.coerceAtLeast(1)
        audibleUntil = maxOf(audibleUntil, now) + durationMs + if (first) LEAD_IN_MS else 0
        _speechAmplitude.value = (pcmLevel(pcm) / 3000f).coerceIn(0f, 1f)
        AudioPlaybackState.onLiveSpeakerState(active = true, nowMs = now)
        writer.execute {
            try {
                if (gen != generation) return@execute
                val out = trackFor(sampleRate) ?: return@execute
                if (first) {
                    // A little silence first gives network hiccups room before the speaker runs dry.
                    val lead = ByteArray((sampleRate * LEAD_IN_MS / 1000).toInt() * 2)
                    writePcmToTrack(lead, sampleRate)
                } else if (out.underrunCount > underrunsSeen) {
                    gaps++
                }
                writePcmToTrack(pcm, sampleRate)
                track?.let { underrunsSeen = runCatching { it.underrunCount }.getOrDefault(underrunsSeen) }
            } catch (t: Throwable) {
                Diag.w("speaker: play error: ${t.message}")
            }
        }
    }

    /** The server finished this reply; the speaker running dry after it is expected. */
    fun endReply() {
        replyOpen = false
        if (!_isSpeaking.value) _speechAmplitude.value = 0f
        AudioPlaybackState.onLiveSpeakerState(active = false)
        if (!_isSpeaking.value) abandonAudioFocus()
    }

    /** Times the speaker ran dry in the middle of a reply since the last call. */
    fun takeGaps(): Int = gaps.also { gaps = 0 }

    /** True while speech is (or is about to be) coming out of the speaker. */
    fun isAudible(tailMs: Long = 300): Boolean =
        SystemClock.elapsedRealtime() < audibleUntil + tailMs ||
            _isSpeaking.value

    /** True while audio output is actively playing OR an offline/system TTS utterance is still initializing. */
    fun hasPendingOrActiveSpeech(): Boolean = TtsPlaybackPolicy.hasPendingOrActiveSpeech(
        isPlaying = _isSpeaking.value,
        hasPendingUtterance = pendingOfflineUtterances.get() > 0 || activeUtteranceId != null,
        hasActivePreview = _previewPlayback.value.state != VoicePreviewState.Idle,
        replyOpen = replyOpen,
        audible = SystemClock.elapsedRealtime() < audibleUntil,
    )

    fun flush() {
        generation++
        audibleUntil = 0
        replyOpen = false
        _speechAmplitude.value = 0f
        AudioPlaybackState.onLiveSpeakerState(active = false)
        stopTts()
        writer.execute {
            resetTrackSafely()
            clearRing() // the track's position starts again at 0
        }
    }

    fun release() {
        generation++
        audibleUntil = 0
        replyOpen = false
        AudioPlaybackState.onLiveSpeakerState(active = false)
        stopTts()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        _isTtsReadyState.value = false
        offlineHindiEngine?.let { engine ->
            runCatching {
                offlineTtsExecutor.execute { engine.release() }
            }.onFailure {
                engine.release()
            }
        }
        writer.execute {
            runCatching { track?.release() }
            track = null
            trackRate = 0
            clearRing()
        }
    }

    private fun resetTrackSafely() {
        val t = track ?: return
        try {
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                t.pause()
                t.flush()
            } else {
                t.release()
                track = null
                trackRate = 0
            }
        } catch (err: Throwable) {
            Diag.w("speaker: resetTrackSafely failed: ${err.message}")
            runCatching { t.release() }
            track = null
            trackRate = 0
        }
    }

    private fun writePcmToTrack(pcm: ByteArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty()) return true
        var out = trackFor(sampleRate) ?: return false
        keep(pcm, sampleRate)
        var offset = 0
        var retriedDead = false
        while (offset < pcm.size) {
            val written = try {
                out.write(pcm, offset, pcm.size - offset)
            } catch (t: Throwable) {
                Diag.w("speaker: AudioTrack write threw: ${t.message}")
                -1
            }
            if (written > 0) {
                offset += written
                continue
            }
            if (!retriedDead && (written == AudioTrack.ERROR_DEAD_OBJECT || written < 0)) {
                retriedDead = true
                Diag.w("speaker: AudioTrack write error ($written), recreating track")
                runCatching { track?.release() }
                track = null
                trackRate = 0
                out = trackFor(sampleRate) ?: return false
                continue
            }
            Diag.w("speaker: AudioTrack write failed with code $written")
            return false
        }
        return true
    }

    private fun trackFor(rate: Int): AudioTrack? {
        if (rate !in 8_000..192_000) {
            Diag.w("speaker: invalid sample rate $rate")
            return null
        }
        track?.let { existing ->
            if (trackRate == rate && existing.state == AudioTrack.STATE_INITIALIZED) {
                if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    val restarted = runCatching { existing.play() }.isSuccess &&
                        existing.playState == AudioTrack.PLAYSTATE_PLAYING
                    if (!restarted) {
                        Diag.w("speaker: existing AudioTrack did not enter playing state")
                        return null
                    }
                }
                return existing
            }
            runCatching { existing.release() }
            track = null
            trackRate = 0
        }

        val minBufferRaw = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val safeMin = if (minBufferRaw > 0) minBufferRaw else (rate / 5) * 2
        val bufferBytes = ((safeMin * 4).coerceAtLeast(8192) + 1) and 1.inv()

        return try {
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (created.state != AudioTrack.STATE_INITIALIZED) {
                Diag.w("speaker: AudioTrack failed to initialize for rate=$rate buf=$bufferBytes")
                runCatching { created.release() }
                return null
            }
            runCatching { created.setVolume(AudioTrack.getMaxVolume()) }
            created.play()
            if (created.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Diag.w("speaker: AudioTrack initialized but did not start playback")
                runCatching { created.release() }
                return null
            }
            clearRing()
            track = created
            trackRate = rate
            underrunsSeen = 0
            Diag.i("speaker: AudioTrack initialized and entered playing state at $rate Hz")
            created
        } catch (t: Throwable) {
            Diag.w("speaker: AudioTrack creation error for rate=$rate: ${t.message}")
            null
        }
    }

    /**
     * What the speaker played, at 16 kHz, ending [backSamples] before the sample playing now
     * (silence where nothing played). For the echo canceller; any thread.
     */
    fun reference(out: ShortArray, backSamples: Int) {
        val t = track
        val rate = trackRate
        if (t == null || rate == 0) {
            out.fill(0)
            return
        }
        val headPos = runCatching {
            if (t.state == AudioTrack.STATE_INITIALIZED) t.playbackHeadPosition else null
        }.getOrNull()
        if (headPos == null) {
            out.fill(0)
            return
        }
        val playing = (headPos.toLong() and 0xFFFFFFFFL) * REF_RATE / rate
        synchronized(ring) {
            val start = playing - backSamples - out.size
            for (i in out.indices) {
                val at = start + i
                out[i] = if (at >= 0 && at < ringWritten && at >= ringWritten - RING) ring[(at % RING).toInt()] else 0
            }
        }
    }

    /** Writer thread: appends [pcm] (16-bit, [rate] Hz) to the ring at 16 kHz. */
    private fun keep(pcm: ByteArray, rate: Int) {
        val n = pcm.size / 2
        if (n == 0) return
        val step = rate.toDouble() / REF_RATE
        synchronized(ring) {
            var pos = resamplePos
            while (pos < n - 1) {
                val i = pos.toInt()
                val a = sample(pcm, i)
                val b = sample(pcm, i + 1)
                ring[(ringWritten % RING).toInt()] = (a + (b - a) * (pos - i)).toInt().toShort()
                ringWritten++
                pos += step
            }
            resamplePos = (pos - n).coerceAtLeast(0.0)
        }
    }

    private fun clearRing() = synchronized(ring) {
        ringWritten = 0
        resamplePos = 0.0
    }

    private fun sample(pcm: ByteArray, i: Int): Int = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)

    private fun cleanSpokenText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return ""
        if (trimmed.startsWith("[Note from the app") || trimmed.startsWith("[Note:")) return ""
        if (trimmed.contains("\"functionCall\"") || trimmed.contains("\"toolResponse\"")) return ""
        return trimmed
    }

    private fun qualityRank(quality: String): Int = when (quality) {
        "Very High" -> 4
        "High" -> 3
        "Standard" -> 2
        else -> 1
    }

    companion object {
        private const val REF_RATE = 16_000
        private const val RING = REF_RATE * 4 // 4 s
        private const val LEAD_IN_MS = 150L
        private const val DUPLICATE_WINDOW_MS = 1_200L
        private const val PREVIEW_START_TIMEOUT_MS = 4_500L
        private val PREVIEW_TIMEOUT_TOKEN = Any()

        @Volatile private var instance: Speaker? = null

        fun get(context: Context): Speaker = instance ?: synchronized(this) {
            instance ?: Speaker(context.applicationContext).also { instance = it }
        }
    }
}
