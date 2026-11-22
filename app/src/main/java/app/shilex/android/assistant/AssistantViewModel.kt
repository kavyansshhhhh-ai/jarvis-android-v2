package app.shilex.android.assistant

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shilex.android.audio.Speaker
import app.shilex.android.data.AppearanceMode
import app.shilex.android.data.AutomationCategory
import app.shilex.android.data.PersonalProfile
import app.shilex.android.data.PersonalProfileStore
import app.shilex.android.data.Settings
import app.shilex.android.learn.Habits
import app.shilex.android.local.ActivityLog
import app.shilex.android.local.LocalCommand
import app.shilex.android.local.LocalPhase
import app.shilex.android.local.LocalTurn
import app.shilex.android.orb.WakeActivationOverlay
import app.shilex.android.task.Task
import app.shilex.android.task.TaskTracker
import app.shilex.android.tools.InstalledAppInfo
import app.shilex.android.tools.InstalledAppsRegistry
import app.shilex.android.wake.VoiceIndicator
import app.shilex.android.wake.VoiceIndicatorSnapshot
import app.shilex.android.wake.VoiceProfile
import app.shilex.android.wake.VoiceSetup
import app.shilex.android.wake.WakeDotSize
import app.shilex.android.wake.WakeDotState
import app.shilex.android.wake.WakeIndicator
import app.shilex.android.wake.WakeState
import app.shilex.android.wake.WakeWordModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The screen's handle on the voice session, which outlives the screen. */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val session = VoiceSession.get(app)
    private val profileStore = PersonalProfileStore(app)

    val state: StateFlow<UiState> = session.state
    val level: StateFlow<Float> = session.level
    val task: StateFlow<Task?> = TaskTracker.current
    private val settings = Settings(app)
    private val local = LocalTurn.get(app)
    val actionRecovery = AgentRecoveryController(maxRetries = 2)

    val localPhase: StateFlow<LocalPhase> = local.phase

    private val _wakeWord = MutableStateFlow(settings.wakeWord)

    /** The wake-word setting, live (Settings screen or the notification's Turn off). */
    val wakeWord: StateFlow<Boolean> = _wakeWord.asStateFlow()

    /** Live background wake-word listener status. */
    val wakeState: StateFlow<WakeState> = VoiceService.wakeState

    /** Live event state for the tiny top-center wake activation dot. */
    val wakeDot: StateFlow<WakeDotState> = WakeIndicator.state

    /** Synchronized voice indicator state (OFF, LISTENING, PROCESSING, SPEAKING, INTERRUPTED, IDLE_TIMEOUT). */
    val voiceIndicator: StateFlow<VoiceIndicatorSnapshot> = VoiceIndicator.state

    private val _personalProfile = MutableStateFlow(PersonalProfile())
    val personalProfile: StateFlow<PersonalProfile> = _personalProfile.asStateFlow()

    private val _appearanceMode = MutableStateFlow(settings.appearanceMode)
    val appearanceModeState: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private val _liquidGlassEnabled = MutableStateFlow(settings.liquidGlassEnabled)
    val liquidGlassEnabledState: StateFlow<Boolean> = _liquidGlassEnabled.asStateFlow()

    private val _installedApps = MutableStateFlow(InstalledAppsRegistry.cachedApps())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _wakeDotSize = MutableStateFlow(WakeDotSize.fromDp(settings.wakeDotSizeDp))
    val wakeDotSizeState: StateFlow<WakeDotSize> = _wakeDotSize.asStateFlow()

    private val _speakResponses = MutableStateFlow(settings.speakResponses)
    val speakResponsesState: StateFlow<Boolean> = _speakResponses.asStateFlow()

    private val _ttsVoiceId = MutableStateFlow(settings.ttsVoiceId)
    val ttsVoiceIdState: StateFlow<String?> = _ttsVoiceId.asStateFlow()

    private val _ttsGenderFilter = MutableStateFlow(settings.ttsGenderFilter)
    val ttsGenderFilterState: StateFlow<String> = _ttsGenderFilter.asStateFlow()

    private val _ttsSpeechRate = MutableStateFlow(settings.ttsSpeechRate)
    val ttsSpeechRateState: StateFlow<Float> = _ttsSpeechRate.asStateFlow()

    private val _ttsPitch = MutableStateFlow(settings.ttsPitch)
    val ttsPitchState: StateFlow<Float> = _ttsPitch.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { profileStore.load() }.getOrNull()?.let { _personalProfile.value = it }
            if (_installedApps.value.isEmpty()) {
                val resolved = runCatching { InstalledAppsRegistry.apps(app) }.getOrDefault(emptyList())
                if (resolved.isNotEmpty()) _installedApps.value = resolved
            }
        }
    }

    // Android holds preference listeners weakly, so keep this one referenced.
    private val watcher = settings.watchKeyed { key ->
        when (key) {
            null, "wake_word" -> {
                _wakeWord.value = settings.wakeWord
                VoiceService.syncState(app)
            }
            "wake_dot_size", "wake_dot_size_dp" -> _wakeDotSize.value = WakeDotSize.fromDp(settings.wakeDotSizeDp)
            "speak_responses" -> _speakResponses.value = settings.speakResponses
            "tts_voice_id" -> _ttsVoiceId.value = settings.ttsVoiceId
            "tts_gender_filter" -> _ttsGenderFilter.value = settings.ttsGenderFilter
            "tts_speech_rate" -> _ttsSpeechRate.value = settings.ttsSpeechRate
            "tts_pitch" -> _ttsPitch.value = settings.ttsPitch
            Settings.PREF_APPEARANCE_MODE -> _appearanceMode.value = settings.appearanceMode
            Settings.PREF_LIQUID_GLASS -> _liquidGlassEnabled.value = settings.liquidGlassEnabled
            "local_commands", "network_speech" -> VoiceService.syncState(app)
        }
    }

    override fun onCleared() {
        settings.unwatch(watcher)
    }
    val activity: StateFlow<List<ActivityLog.Entry>> = ActivityLog.entries

    /** Home's quick actions: run on the phone at once; returns the reply to show. */
    fun quick(command: LocalCommand): String = local.runNow(command)

    fun clearActivity() = ActivityLog.clear()

    fun savePersonalProfile(profile: PersonalProfile) {
        val clean = profile.sanitized()
        profileStore.save(clean)
        _personalProfile.value = clean
    }

    fun clearPersonalProfile() {
        profileStore.clear()
        _personalProfile.value = PersonalProfile()
    }

    fun refreshInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = runCatching {
                InstalledAppsRegistry.apps(getApplication(), forceRefresh = true)
            }.getOrDefault(_installedApps.value)
        }
    }

    fun launchInstalledApp(packageName: String): Boolean {
        val context = getApplication<Application>()
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private val habits get() = Habits.get(getApplication())

    var learning: Boolean
        get() = settings.learning
        set(value) { settings.learning = value }

    /** Home's quick actions: the user's most used phone commands, once there are some. */
    fun favourites(): List<LocalCommand> = if (settings.learning) habits.top(4) else emptyList()

    /** What has been learned, in plain words for Settings. */
    fun learnedSummary(): String {
        val summary = habits.summary(java.time.LocalTime.now().hour)
        val phrases = habits.learnedPhrases()
        return listOfNotNull(
            summary.ifEmpty { null },
            if (phrases > 0) "$phrases of your own phrases now run on the phone." else null,
        ).joinToString(" ").ifEmpty { "Nothing yet." }
    }

    fun forgetLearned() = habits.forget()

    var appearanceMode: AppearanceMode
        get() = _appearanceMode.value
        set(value) {
            settings.appearanceMode = value
            _appearanceMode.value = value
        }

    var liquidGlassEnabled: Boolean
        get() = _liquidGlassEnabled.value
        set(value) {
            settings.liquidGlassEnabled = value
            _liquidGlassEnabled.value = value
        }

    var onboarded: Boolean
        get() = settings.onboarded
        set(value) { settings.onboarded = value }

    var localCommands: Boolean
        get() = settings.localCommands
        set(value) { settings.localCommands = value }

    var talkOver: Boolean
        get() = settings.talkOver
        set(value) { settings.talkOver = value }

    var waveToStop: Boolean
        get() = settings.waveToStop
        set(value) { settings.waveToStop = value }

    var speakResponses: Boolean
        get() = settings.speakResponses
        set(value) {
            settings.speakResponses = value
            _speakResponses.value = value
        }

    val speaker: Speaker get() = Speaker.get(getApplication())

    var ttsVoiceId: String?
        get() = settings.ttsVoiceId
        set(value) {
            settings.ttsVoiceId = value
            _ttsVoiceId.value = value
            speaker.applySavedVoiceAndSettings()
        }

    var ttsSpeechRate: Float
        get() = settings.ttsSpeechRate
        set(value) {
            val clamped = value.coerceIn(0.5f, 2.0f)
            settings.ttsSpeechRate = clamped
            _ttsSpeechRate.value = clamped
            speaker.applySavedVoiceAndSettings()
        }

    var ttsPitch: Float
        get() = settings.ttsPitch
        set(value) {
            val clamped = value.coerceIn(0.5f, 1.5f)
            settings.ttsPitch = clamped
            _ttsPitch.value = clamped
            speaker.applySavedVoiceAndSettings()
        }

    var ttsGenderFilter: String
        get() = settings.ttsGenderFilter
        set(value) {
            val normalized = Settings.normalizeGenderFilter(value)
            settings.ttsGenderFilter = normalized
            _ttsGenderFilter.value = normalized
        }

    fun refreshTtsVoices() {
        speaker.refreshAudioOutputState()
        speaker.discoverVoices()
        speaker.applySavedVoiceAndSettings()
    }

    fun restoreDefaultTtsVoice() {
        settings.restoreDefaultTtsVoice()
        _ttsVoiceId.value = settings.ttsVoiceId
        _ttsGenderFilter.value = settings.ttsGenderFilter
        _ttsSpeechRate.value = settings.ttsSpeechRate
        _ttsPitch.value = settings.ttsPitch
        speaker.applySavedVoiceAndSettings()
    }

    var networkSpeech: Boolean
        get() = settings.networkSpeech
        set(value) { settings.networkSpeech = value }

    /** "on-device", "offline", "network" or null (Gemini hears everything). */
    val speechEngine: String? get() = local.recogniser()

    /** Samsung and others stop background apps unless they're exempt from battery saving. */
    val batteryExempt: Boolean
        get() = getApplication<Application>().getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(getApplication<Application>().packageName)

    suspend fun saveKey(key: String): String? = session.saveKey(key)

    fun forgetKey() = session.forgetKey()

    fun start() = session.start()

    fun sendTextMessage(text: String) = session.sendTextMessage(text)

    fun stop() {
        actionRecovery.reset()
        local.stop()
        session.stop("Stop button")
    }

    fun shutdownCompletely(reason: String = "user requested shutdown") {
        actionRecovery.reset()
        session.shutdownCompletely(reason)
    }

    fun interrupt() {
        local.interrupt()
        session.interrupt()
    }

    fun permissionAnswered(granted: Boolean) = session.permissionAnswered(granted)

    fun onPhotoPicked(uri: Uri?) = session.onPhotoPicked(uri)

    fun onGalleryDeleteResult(confirmed: Boolean) = session.onGalleryDeleteResult(confirmed)

    fun isAutomationEnabled(category: AutomationCategory): Boolean = settings.isAutomationEnabled(category)

    fun setAutomationEnabled(category: AutomationCategory, enabled: Boolean) =
        settings.setAutomationEnabled(category, enabled)

    fun micUnavailable() = session.micUnavailable()

    /** Offline wake-word detection is available whenever Vosk or ONNX models exist. */
    val wakeWordAvailable: Boolean = WakeWordModel.available(app)

    /** True only when optional openWakeWord ONNX assets are present for voice-profile calibration. */
    val wakeOnnxAvailable: Boolean = WakeWordModel.onnxAvailable(app)

    val wakeWordOn: Boolean get() = Settings(getApplication()).wakeWord

    val voiceSaved: Boolean get() = VoiceProfile.exists(getApplication())

    /** Voice setup: one sample. Pauses the wake listener and any conversation meanwhile. */
    suspend fun recordVoiceSample(speakNow: () -> Unit): VoiceSetup.Sample {
        session.stop("voice setup")
        VoiceService.voiceSetup(true)
        delay(MIC_HANDOVER_MS) // let the other mic users close first
        return try {
            VoiceSetup.record(getApplication(), speakNow)
        } finally {
            VoiceService.voiceSetup(false)
        }
    }

    fun saveVoice(windows: List<FloatArray>) {
        VoiceProfile.save(getApplication(), VoiceProfile.create(windows))
        VoiceService.voiceSetup(false) // restarts the listener with the new profile
    }

    fun forgetVoice() {
        VoiceProfile.forget(getApplication())
        VoiceService.voiceSetup(false)
    }

    /** Call while the screen is visible: turning it on starts the listening service. */
    fun setWakeWord(on: Boolean) {
        Settings(getApplication()).wakeWord = on
        _wakeWord.value = on
        if (!on) WakeActivationOverlay.dismiss(getApplication())
        VoiceService.start(getApplication())
    }

    var wakeIndicator: Boolean
        get() = settings.wakeIndicator
        set(value) {
            settings.wakeIndicator = value
            if (!value) WakeActivationOverlay.dismiss(getApplication())
        }

    var wakeDotSize: WakeDotSize
        get() = _wakeDotSize.value
        set(value) {
            val clamped = WakeDotSize.fromDp(value.coreDiameterDp)
            settings.wakeDotSizeDp = clamped.coreDiameterDp
            _wakeDotSize.value = clamped
        }

    var wakeDotDurationMs: Long
        get() = settings.wakeDotDurationMs
        set(value) { settings.wakeDotDurationMs = value }

    fun refreshWakeState() {
        if (VoiceService.wakeWordWanted(getApplication())) {
            VoiceService.start(getApplication())
        } else {
            VoiceService.syncState(getApplication())
        }
    }

    private companion object {
        const val MIC_HANDOVER_MS = 400L
    }
}
