package app.shilex.android.data

import android.content.Context
import android.content.SharedPreferences
import app.shilex.android.wake.WakeDotSize
import app.shilex.android.wake.WakeIndicatorController

enum class AppearanceMode(val label: String) {
    Dark("Dark"),
    Light("Light"),
    System("System");

    companion object {
        fun from(value: String?): AppearanceMode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Dark
    }
}

/** Plain preferences (no secrets). Defaults match the desktop app. */
enum class AutomationCategory(
    val key: String,
    val title: String,
    val summary: String,
    val defaultEnabled: Boolean = true,
) {
    WhatsAppMessages(
        key = "auto_whatsapp_messages",
        title = "WhatsApp message automation",
        summary = "Type drafts and tap Send for clear WhatsApp commands without repetitive internal Allow popups.",
    ),
    Calling(
        key = "auto_calling",
        title = "Calling automation",
        summary = "Start unambiguous phone and WhatsApp audio/video calls cleanly after your command.",
    ),
    AppOpening(
        key = "auto_open_apps",
        title = "App opening",
        summary = "Open installed apps directly when asked without extra internal confirmations.",
    ),
    MediaPlayback(
        key = "auto_media_playback",
        title = "Media playback",
        summary = "Search, play, pause, and skip music on your preferred music app without extra popups.",
    ),
    GalleryManagement(
        key = "auto_gallery_management",
        title = "Gallery management",
        summary = "Locate gallery photos cleanly (deletion still ALWAYS asks explicit yes/no confirmation).",
    ),
    ScreenReading(
        key = "auto_screen_reading",
        title = "Screen reading",
        summary = "Read visible non-sensitive app screens directly when requested.",
    ),
    ScreenInteraction(
        key = "auto_screen_interaction",
        title = "On-screen interaction",
        summary = "Allow safe non-payment taps, typing, and scrolling inside non-financial apps.",
    ),
    PlayStoreNavigation(
        key = "auto_play_store",
        title = "Play Store navigation & free installs",
        summary = "Search apps in Google Play Store and tap free Install (always stops for age, payment, or sign-in).",
    );

    val label: String get() = title
    val description: String get() = summary
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var appearanceMode: AppearanceMode
        get() = AppearanceMode.from(prefs.getString(PREF_APPEARANCE_MODE, AppearanceMode.Dark.name))
        set(value) = prefs.edit().putString(PREF_APPEARANCE_MODE, value.name).apply()

    /** iOS-inspired Liquid Glass styling. Enabled by default for new installations. */
    var liquidGlassEnabled: Boolean
        get() = prefs.getBoolean(PREF_LIQUID_GLASS, DEFAULT_LIQUID_GLASS_ENABLED)
        set(value) = prefs.edit().putBoolean(PREF_LIQUID_GLASS, value).apply()

    fun isAutomationEnabled(category: AutomationCategory): Boolean =
        prefs.getBoolean(category.key, category.defaultEnabled)

    fun setAutomationEnabled(category: AutomationCategory, enabled: Boolean) {
        prefs.edit().putBoolean(category.key, enabled).apply()
    }

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value).apply()

    var voice: String
        get() = prefs.getString("voice", "Puck") ?: "Puck"
        set(value) = prefs.edit().putString("voice", value).apply()

    /** Speak responses aloud using the on-device TTS engine. Default: true. */
    var speakResponses: Boolean
        get() = prefs.getBoolean("speak_responses", true)
        set(value) = prefs.edit().putBoolean("speak_responses", value).apply()

    /** Selected Android TTS voice identifier (null = system/Hindi default). */
    var ttsVoiceId: String?
        get() = prefs.getString("tts_voice_id", null)
        set(value) = prefs.edit().putString("tts_voice_id", value).apply()

    /** TTS speech rate multiplier (0.5x .. 2.0x). Default: 1.0f. */
    var ttsSpeechRate: Float
        get() = prefs.getFloat("tts_speech_rate", 1.0f).coerceIn(0.5f, 2.0f)
        set(value) = prefs.edit().putFloat("tts_speech_rate", value.coerceIn(0.5f, 2.0f)).apply()

    /** TTS pitch multiplier (0.5x .. 1.5x). Default: 1.0f. */
    var ttsPitch: Float
        get() = prefs.getFloat("tts_pitch", 1.0f).coerceIn(0.5f, 1.5f)
        set(value) = prefs.edit().putFloat("tts_pitch", value.coerceIn(0.5f, 1.5f)).apply()

    /** Voice selection category ("FEMALE" or "MALE"). Default: "FEMALE". */
    var ttsGenderFilter: String
        get() = normalizeGenderFilter(prefs.getString("tts_gender_filter", GENDER_FEMALE))
        set(value) = prefs.edit().putString("tts_gender_filter", normalizeGenderFilter(value)).apply()

    fun restoreDefaultTtsVoice() {
        prefs.edit()
            .remove("tts_voice_id")
            .putString("tts_gender_filter", GENDER_FEMALE)
            .putFloat("tts_speech_rate", 1.0f)
            .putFloat("tts_pitch", 1.0f)
            .apply()
    }

    /** "auto", "english", "hindi" or "hinglish", as on desktop. */
    var replyLanguage: String
        get() = prefs.getString("reply_language", "auto") ?: "auto"
        set(value) = prefs.edit().putString("reply_language", value).apply()

    /** Mute the mic while the assistant speaks, unless headphones are in use. */
    var halfDuplex: Boolean
        get() = prefs.getBoolean("half_duplex", true)
        set(value) = prefs.edit().putBoolean("half_duplex", value).apply()

    /** Talk over a reply on the loudspeaker (audio/TalkOver, with the echo canceller). */
    var talkOver: Boolean
        get() = prefs.getBoolean("talk_over", true)
        set(value) = prefs.edit().putBoolean("talk_over", value).apply()

    /** Wave over the proximity sensor to cut a reply off (audio/WaveStop). */
    var waveToStop: Boolean
        get() = prefs.getBoolean("wave_to_stop", true)
        set(value) = prefs.edit().putBoolean("wave_to_stop", value).apply()

    /** Listen on the phone for the wake phrase between sessions (debug builds only for now). */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(value) = prefs.edit().putBoolean("wake_word", value).apply()

    /** Subtle top-center activation dot when the wake phrase triggers. */
    var wakeIndicator: Boolean
        get() = prefs.getBoolean("wake_indicator", true)
        set(value) = prefs.edit().putBoolean("wake_indicator", value).apply()

    /** Visible size for the wake dot ("dp9".."dp50", or legacy preset key). Default: 12dp. */
    var wakeDotSize: String
        get() = WakeDotSize.fromDp(wakeDotSizeDp).key
        set(value) {
            val resolved = WakeDotSize.fromKey(value)
            prefs.edit()
                .putString("wake_dot_size", resolved.key)
                .putInt("wake_dot_size_dp", resolved.coreDiameterDp)
                .apply()
        }

    /** Continuous wake dot core diameter in dp (9dp–50dp). Default: 12dp. */
    var wakeDotSizeDp: Int
        get() = if (prefs.contains("wake_dot_size_dp")) {
            WakeDotSize.clampDp(prefs.getInt("wake_dot_size_dp", WakeDotSize.DEFAULT_DP))
        } else {
            WakeDotSize.fromKey(prefs.getString("wake_dot_size", WakeDotSize.Standard.key)).coreDiameterDp
        }
        set(value) {
            val resolved = WakeDotSize.fromDp(value)
            prefs.edit()
                .putInt("wake_dot_size_dp", resolved.coreDiameterDp)
                .putString("wake_dot_size", resolved.key)
                .apply()
        }

    /** How long the wake dot stays visible (ms), clamped to safe bounds. Default: 2800ms (~2.8s). */
    var wakeDotDurationMs: Long
        get() = WakeIndicatorController.clampDurationMs(
            prefs.getLong("wake_dot_duration_ms", WakeIndicatorController.DISPLAY_DURATION_MS),
        )
        set(value) = prefs.edit().putLong("wake_dot_duration_ms", WakeIndicatorController.clampDurationMs(value)).apply()

    /** After the wake word, simple commands run on the phone (no Gemini) when it can hear them. */
    var localCommands: Boolean
        get() = prefs.getBoolean("local_commands", true)
        set(value) = prefs.edit().putBoolean("local_commands", value).apply()

    /**
     * Let local commands use the phone's network speech service when it has no on-device one.
     * Off by default: that service sends the audio to its provider (usually Google).
     */
    var networkSpeech: Boolean
        get() = prefs.getBoolean("network_speech", false)
        set(value) = prefs.edit().putBoolean("network_speech", value).apply()

    /** Learn on the phone how this user uses JARVIS (learn/Habits). */
    var learning: Boolean
        get() = prefs.getBoolean("learning", true)
        set(value) = prefs.edit().putBoolean("learning", value).apply()

    /** The first-run setup was finished (or skipped) once. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** The floating orb over other apps (needs "Display over other apps"). */
    var floatingOrb: Boolean
        get() = prefs.getBoolean("floating_orb", false)
        set(value) = prefs.edit().putBoolean("floating_orb", value).apply()

    /** Where the user last left the floating orb, in screen pixels; -1 = not moved yet. */
    var orbX: Int
        get() = prefs.getInt("orb_x", -1)
        set(value) = prefs.edit().putInt("orb_x", value).apply()

    var orbY: Int
        get() = prefs.getInt("orb_y", -1)
        set(value) = prefs.edit().putInt("orb_y", value).apply()

    /** The last city asked about; weather questions without a city use it. */
    var homeCity: String
        get() = prefs.getString("home_city", "") ?: ""
        set(value) = prefs.edit().putString("home_city", value).apply()

    /** Calls [onChange] after any setting changes, passing the changed key. */
    fun watchKeyed(onChange: (String?) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChange(key) }
            .also(prefs::registerOnSharedPreferenceChangeListener)

    fun unwatch(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    val speechLanguages: List<String> = listOf("en-IN", "hi-IN")

    companion object {
        const val PREF_APPEARANCE_MODE = "appearance_mode"
        const val PREF_LIQUID_GLASS = "liquid_glass_enabled"
        const val DEFAULT_LIQUID_GLASS_ENABLED = true
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        const val GENDER_FEMALE = "FEMALE"
        const val GENDER_MALE = "MALE"

        fun normalizeGenderFilter(raw: String?): String = when {
            raw.equals(GENDER_MALE, ignoreCase = true) -> GENDER_MALE
            else -> GENDER_FEMALE
        }
    }
}
