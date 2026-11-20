package app.shilex.android.audio

import android.content.Context
import java.util.Locale

/**
 * Automatic gender classification for genuine Hindi (India) voices.
 * Uses ONLY reliable engine/model metadata; never guesses from opaque voice names or pitch.
 */
enum class VoiceGender(val label: String) {
    Female("Female"),
    Male("Male"),
    Unspecified("Unspecified"),
}

/** Backing offline neural synthesizer engine type for bundled Hindi voices. */
enum class BundledEngineKind {
    PiperVits,
    KokoroMultiLang,
    Supertonic,
}

/** Lightweight, JVM-testable representation of an Android TextToSpeech Voice. */
data class RawTtsVoice(
    val id: String,
    val locale: Locale,
    val isNetworkRequired: Boolean,
    val qualityScore: Int,
    val features: Set<String>? = null,
)

/**
 * Clean representation of a genuine Hindi (India) voice ready for the JARVIS VOICE UI.
 */
data class TtsVoiceInfo(
    val id: String,
    val displayName: String,
    val gender: VoiceGender,
    val locale: Locale,
    val isNetworkRequired: Boolean,
    val qualityScore: Int = 300,
    val isInstalled: Boolean = true,
    val isBundled: Boolean = false,
    val modelAssetPath: String? = null,
    val sampleAssetPath: String? = null,
    val licenseNotice: String? = null,
    val datasetSource: String? = null,
) {
    val isOfflineCapable: Boolean
        get() = (isBundled || isInstalled) && !isNetworkRequired
}

data class VoiceValidationResult(
    val isValid: Boolean,
    val reason: String = "",
)

/**
 * Genuine offline Hindi neural voice model bundled directly inside JARVIS.
 */
data class BundledHindiVoice(
    val id: String,
    val displayName: String,
    val gender: VoiceGender,
    val locale: Locale = Locale("hi", "IN"),
    val engineKind: BundledEngineKind = BundledEngineKind.PiperVits,
    val modelAssetPath: String,
    val tokensAssetPath: String,
    val configAssetPath: String,
    val voicesAssetPath: String = "",
    val durationPredictorAssetPath: String = "",
    val textEncoderAssetPath: String = "",
    val vectorEstimatorAssetPath: String = "",
    val vocoderAssetPath: String = "",
    val ttsJsonAssetPath: String = "",
    val unicodeIndexerAssetPath: String = "",
    val voiceStyleAssetPath: String = "",
    val speakerId: Int = 0,
    val lang: String = "hi",
    val sampleAssetPath: String = "",
    val sampleRate: Int = 22050,
    val licenseNotice: String,
    val datasetSource: String,
) {
    fun toTtsVoiceInfo(): TtsVoiceInfo = TtsVoiceInfo(
        id = id,
        displayName = displayName,
        gender = gender,
        locale = locale,
        isNetworkRequired = false,
        qualityScore = 1000,
        isInstalled = true,
        isBundled = true,
        modelAssetPath = modelAssetPath,
        sampleAssetPath = sampleAssetPath.ifEmpty { null },
        licenseNotice = licenseNotice,
        datasetSource = datasetSource,
    )
}

/**
 * Verified offline Hindi voices bundled inside the APK assets and synthesized offline via Sherpa-ONNX.
 * Every voice is backed by a distinct genuine trained speaker / model with verified gender metadata;
 * zero pitch-shifted clones or guessed genders.
 *
 * Total bundled genuine offline neural Hindi voices (5 Female + 5 Male = 10):
 * - 5 Female: Priyamvada (Piper/AI4Bharat), hf_alpha (Kokoro), hf_beta (Kokoro), F1 (Supertonic-3), F2 (Supertonic-3)
 * - 5 Male: Pratham (Piper/AI4Bharat), Rohan (Piper/IndicTTS), hm_omega (Kokoro), hm_psi (Kokoro), M1 (Supertonic-3)
 */
object BundledHindiVoices {
    const val ESPEAK_DATA_ASSET_DIR = "voices/espeak-ng-data"
    const val ESPEAK_MARKER_ASSET = "voices/espeak-ng-data/hi_dict"
    val REQUIRED_ESPEAK_FILES = listOf(
        "hi_dict",
        "phontab",
        "phondata",
        "phonindex",
        "intonations",
        "lang/inc/hi",
    )

    private const val SUPERTONIC_DURATION = "voices/supertonic/duration_predictor.int8.onnx"
    private const val SUPERTONIC_TEXT_ENCODER = "voices/supertonic/text_encoder.int8.onnx"
    private const val SUPERTONIC_VECTOR_ESTIMATOR = "voices/supertonic/vector_estimator.int8.onnx"
    private const val SUPERTONIC_VOCODER = "voices/supertonic/vocoder.int8.onnx"
    private const val SUPERTONIC_TTS_JSON = "voices/supertonic/tts.json"
    private const val SUPERTONIC_INDEXER = "voices/supertonic/unicode_indexer.bin"
    private const val SUPERTONIC_VOICE_STYLE = "voices/supertonic/voice.bin"

    val PRIYAMVADA = BundledHindiVoice(
        id = "bundled_hi_female_priyamvada",
        displayName = "Hindi Female 1",
        gender = VoiceGender.Female,
        engineKind = BundledEngineKind.PiperVits,
        modelAssetPath = "voices/piper/priyamvada/hi_IN-priyamvada-medium.onnx",
        tokensAssetPath = "voices/piper/priyamvada/tokens.txt",
        configAssetPath = "voices/piper/priyamvada/hi_IN-priyamvada-medium.onnx.json",
        sampleRate = 22050,
        licenseNotice = "CC BY-NC-SA 4.0",
        datasetSource = "AI4Bharat IndicNLP Hindi Female (Piper VITS int8)",
    )

    val HF_ALPHA = BundledHindiVoice(
        id = "bundled_hi_female_kokoro_alpha",
        displayName = "Hindi Female 2",
        gender = VoiceGender.Female,
        engineKind = BundledEngineKind.KokoroMultiLang,
        modelAssetPath = "voices/kokoro/model.int8.onnx",
        tokensAssetPath = "voices/kokoro/tokens.txt",
        configAssetPath = "voices/kokoro/tokens.txt",
        voicesAssetPath = "voices/kokoro/voices.bin",
        speakerId = 31,
        lang = "hi",
        sampleRate = 24000,
        licenseNotice = "Apache-2.0",
        datasetSource = "Kokoro-82M Hindi Female hf_alpha (sid 31)",
    )

    val HF_BETA = BundledHindiVoice(
        id = "bundled_hi_female_kokoro_beta",
        displayName = "Hindi Female 3",
        gender = VoiceGender.Female,
        engineKind = BundledEngineKind.KokoroMultiLang,
        modelAssetPath = "voices/kokoro/model.int8.onnx",
        tokensAssetPath = "voices/kokoro/tokens.txt",
        configAssetPath = "voices/kokoro/tokens.txt",
        voicesAssetPath = "voices/kokoro/voices.bin",
        speakerId = 32,
        lang = "hi",
        sampleRate = 24000,
        licenseNotice = "Apache-2.0",
        datasetSource = "Kokoro-82M Hindi Female hf_beta (sid 32)",
    )

    val SUPERTONIC_F1 = BundledHindiVoice(
        id = "bundled_hi_female_supertonic_f1",
        displayName = "Hindi Female 4",
        gender = VoiceGender.Female,
        engineKind = BundledEngineKind.Supertonic,
        modelAssetPath = SUPERTONIC_VOCODER,
        tokensAssetPath = SUPERTONIC_INDEXER,
        configAssetPath = SUPERTONIC_TTS_JSON,
        voicesAssetPath = SUPERTONIC_VOICE_STYLE,
        durationPredictorAssetPath = SUPERTONIC_DURATION,
        textEncoderAssetPath = SUPERTONIC_TEXT_ENCODER,
        vectorEstimatorAssetPath = SUPERTONIC_VECTOR_ESTIMATOR,
        vocoderAssetPath = SUPERTONIC_VOCODER,
        ttsJsonAssetPath = SUPERTONIC_TTS_JSON,
        unicodeIndexerAssetPath = SUPERTONIC_INDEXER,
        voiceStyleAssetPath = SUPERTONIC_VOICE_STYLE,
        speakerId = 0,
        lang = "hi",
        sampleRate = 44100,
        licenseNotice = "MIT",
        datasetSource = "Supertone Supertonic-3 Multilingual Hindi Female F1 (sid 0)",
    )

    val SUPERTONIC_F2 = BundledHindiVoice(
        id = "bundled_hi_female_supertonic_f2",
        displayName = "Hindi Female 5",
        gender = VoiceGender.Female,
        engineKind = BundledEngineKind.Supertonic,
        modelAssetPath = SUPERTONIC_VOCODER,
        tokensAssetPath = SUPERTONIC_INDEXER,
        configAssetPath = SUPERTONIC_TTS_JSON,
        voicesAssetPath = SUPERTONIC_VOICE_STYLE,
        durationPredictorAssetPath = SUPERTONIC_DURATION,
        textEncoderAssetPath = SUPERTONIC_TEXT_ENCODER,
        vectorEstimatorAssetPath = SUPERTONIC_VECTOR_ESTIMATOR,
        vocoderAssetPath = SUPERTONIC_VOCODER,
        ttsJsonAssetPath = SUPERTONIC_TTS_JSON,
        unicodeIndexerAssetPath = SUPERTONIC_INDEXER,
        voiceStyleAssetPath = SUPERTONIC_VOICE_STYLE,
        speakerId = 1,
        lang = "hi",
        sampleRate = 44100,
        licenseNotice = "MIT",
        datasetSource = "Supertone Supertonic-3 Multilingual Hindi Female F2 (sid 1)",
    )

    val PRATHAM = BundledHindiVoice(
        id = "bundled_hi_male_pratham",
        displayName = "Hindi Male 1",
        gender = VoiceGender.Male,
        engineKind = BundledEngineKind.PiperVits,
        modelAssetPath = "voices/piper/pratham/hi_IN-pratham-medium.onnx",
        tokensAssetPath = "voices/piper/pratham/tokens.txt",
        configAssetPath = "voices/piper/pratham/hi_IN-pratham-medium.onnx.json",
        sampleRate = 22050,
        licenseNotice = "CC BY-NC-SA 4.0",
        datasetSource = "AI4Bharat IndicNLP Hindi Male (Piper VITS int8)",
    )

    val ROHAN = BundledHindiVoice(
        id = "bundled_hi_male_rohan",
        displayName = "Hindi Male 2",
        gender = VoiceGender.Male,
        engineKind = BundledEngineKind.PiperVits,
        modelAssetPath = "voices/piper/rohan/hi_IN-rohan-medium.onnx",
        tokensAssetPath = "voices/piper/rohan/tokens.txt",
        configAssetPath = "voices/piper/rohan/hi_IN-rohan-medium.onnx.json",
        sampleRate = 22050,
        licenseNotice = "IIT Madras IndicTTS License",
        datasetSource = "IIT Madras IndicTTS Hindi Male (Piper VITS int8)",
    )

    val HM_OMEGA = BundledHindiVoice(
        id = "bundled_hi_male_kokoro_omega",
        displayName = "Hindi Male 3",
        gender = VoiceGender.Male,
        engineKind = BundledEngineKind.KokoroMultiLang,
        modelAssetPath = "voices/kokoro/model.int8.onnx",
        tokensAssetPath = "voices/kokoro/tokens.txt",
        configAssetPath = "voices/kokoro/tokens.txt",
        voicesAssetPath = "voices/kokoro/voices.bin",
        speakerId = 33,
        lang = "hi",
        sampleRate = 24000,
        licenseNotice = "Apache-2.0",
        datasetSource = "Kokoro-82M Hindi Male hm_omega (sid 33)",
    )

    val HM_PSI = BundledHindiVoice(
        id = "bundled_hi_male_kokoro_psi",
        displayName = "Hindi Male 4",
        gender = VoiceGender.Male,
        engineKind = BundledEngineKind.KokoroMultiLang,
        modelAssetPath = "voices/kokoro/model.int8.onnx",
        tokensAssetPath = "voices/kokoro/tokens.txt",
        configAssetPath = "voices/kokoro/tokens.txt",
        voicesAssetPath = "voices/kokoro/voices.bin",
        speakerId = 34,
        lang = "hi",
        sampleRate = 24000,
        licenseNotice = "Apache-2.0",
        datasetSource = "Kokoro-82M Hindi Male hm_psi (sid 34)",
    )

    val SUPERTONIC_M1 = BundledHindiVoice(
        id = "bundled_hi_male_supertonic_m1",
        displayName = "Hindi Male 5",
        gender = VoiceGender.Male,
        engineKind = BundledEngineKind.Supertonic,
        modelAssetPath = SUPERTONIC_VOCODER,
        tokensAssetPath = SUPERTONIC_INDEXER,
        configAssetPath = SUPERTONIC_TTS_JSON,
        voicesAssetPath = SUPERTONIC_VOICE_STYLE,
        durationPredictorAssetPath = SUPERTONIC_DURATION,
        textEncoderAssetPath = SUPERTONIC_TEXT_ENCODER,
        vectorEstimatorAssetPath = SUPERTONIC_VECTOR_ESTIMATOR,
        vocoderAssetPath = SUPERTONIC_VOCODER,
        ttsJsonAssetPath = SUPERTONIC_TTS_JSON,
        unicodeIndexerAssetPath = SUPERTONIC_INDEXER,
        voiceStyleAssetPath = SUPERTONIC_VOICE_STYLE,
        speakerId = 5,
        lang = "hi",
        sampleRate = 44100,
        licenseNotice = "MIT",
        datasetSource = "Supertone Supertonic-3 Multilingual Hindi Male M1 (sid 5)",
    )

    val ALL: List<BundledHindiVoice> = listOf(
        PRIYAMVADA,
        HF_ALPHA,
        HF_BETA,
        SUPERTONIC_F1,
        SUPERTONIC_F2,
        PRATHAM,
        ROHAN,
        HM_OMEGA,
        HM_PSI,
        SUPERTONIC_M1,
    )

    /** True: in-app Sherpa-ONNX offline neural TTS runtime is integrated and reads bundled APK assets. */
    const val IS_OFFLINE_SYNTHESIS_INTEGRATED = true

    fun find(voiceId: String?): BundledHindiVoice? = ALL.firstOrNull { it.id == voiceId }

    fun validateCatalog(voices: List<BundledHindiVoice> = ALL): VoiceValidationResult {
        if (voices.isEmpty()) return VoiceValidationResult(false, "Bundled voice catalog is empty.")
        val ids = mutableSetOf<String>()
        val speakerKeys = mutableSetOf<String>()
        for (voice in voices) {
            val single = VoiceClassifier.validateBundledVoice(voice)
            if (!single.isValid) return single
            if (!ids.add(voice.id)) {
                return VoiceValidationResult(false, "Duplicate voice ID detected: ${voice.id}")
            }
            val speakerKey = "${voice.engineKind}:${voice.modelAssetPath}:${voice.speakerId}"
            if (!speakerKeys.add(speakerKey)) {
                return VoiceValidationResult(false, "Duplicate voice speaker mapping for ${voice.id}")
            }
        }
        val femaleCount = voices.count { it.gender == VoiceGender.Female }
        val maleCount = voices.count { it.gender == VoiceGender.Male }
        if (femaleCount != HindiVoiceCatalog.TARGET_COUNT_PER_CATEGORY ||
            maleCount != HindiVoiceCatalog.TARGET_COUNT_PER_CATEGORY
        ) {
            return VoiceValidationResult(
                false,
                "Expected 5 Female and 5 Male voices, found $femaleCount Female / $maleCount Male.",
            )
        }
        return VoiceValidationResult(true)
    }

    fun isModelBundled(context: Context?, voice: BundledHindiVoice): Boolean {
        if (!IS_OFFLINE_SYNTHESIS_INTEGRATED || context == null) return false
        if (!VoiceClassifier.validateBundledVoice(voice).isValid) return false
        return when (voice.engineKind) {
            BundledEngineKind.Supertonic -> listOf(
                voice.durationPredictorAssetPath,
                voice.textEncoderAssetPath,
                voice.vectorEstimatorAssetPath,
                voice.vocoderAssetPath,
                voice.ttsJsonAssetPath,
                voice.unicodeIndexerAssetPath,
                voice.voiceStyleAssetPath,
            ).all { it.isNotEmpty() && (assetFileReady(context, it) || assetExists(context, it)) }

            BundledEngineKind.PiperVits, BundledEngineKind.KokoroMultiLang -> {
                val hasModel = assetFileReady(context, voice.modelAssetPath) || assetExists(context, voice.modelAssetPath)
                val hasTokens = assetFileReady(context, voice.tokensAssetPath) || assetExists(context, voice.tokensAssetPath)
                val hasConfig = voice.configAssetPath.isEmpty() || assetFileReady(context, voice.configAssetPath) || assetExists(context, voice.configAssetPath)
                val hasEspeak = REQUIRED_ESPEAK_FILES.all { rel ->
                    assetFileReady(context, "espeak-ng-data/$rel") || assetExists(context, "$ESPEAK_DATA_ASSET_DIR/$rel")
                }
                val hasVoices = voice.voicesAssetPath.isEmpty() || assetFileReady(context, voice.voicesAssetPath) || assetExists(context, voice.voicesAssetPath)
                hasModel && hasTokens && hasConfig && hasEspeak && hasVoices
            }
        }
    }

    private fun assetFileReady(context: Context, assetPath: String): Boolean =
        runCatching {
            val f = java.io.File(context.filesDir, "tts/$assetPath")
            f.exists() && f.length() > 0L
        }.getOrDefault(false)

    private fun assetExists(context: Context, assetPath: String): Boolean =
        runCatching { context.assets.open(assetPath).use { it.available() >= 0 } }.getOrDefault(false)

    fun availableForRuntime(context: Context?): List<TtsVoiceInfo> =
        ALL.filter { isModelBundled(context, it) }.map { it.toTtsVoiceInfo() }
}

/**
 * Structured catalog of genuine Hindi (India) voices separated into
 * Female Voices (max 5) and Male Voices (max 5).
 */
data class HindiVoiceCatalog(
    val femaleVoices: List<TtsVoiceInfo> = emptyList(),
    val maleVoices: List<TtsVoiceInfo> = emptyList(),
    val totalFemaleFound: Int = 0,
    val totalMaleFound: Int = 0,
    val totalHindiVoicesCount: Int = 0,
    val installedHindiVoicesCount: Int = 0,
    val bundledVoicesCount: Int = 0,
) {
    companion object {
        const val TARGET_COUNT_PER_CATEGORY = 5
    }
}

/** Internal diagnostics about device Hindi voice availability. */
data class TtsEngineDiagnostics(
    val activeEnginePackage: String? = null,
    val isGoogleSpeechServicesActive: Boolean = false,
    val isGoogleSpeechServicesInstalled: Boolean = false,
    val hindiLanguageAvailability: Int? = null,
    val totalEngineVoicesCount: Int = 0,
    val totalHindiVoicesCount: Int = 0,
    val installedHindiVoicesCount: Int = 0,
    val bundledHindiVoicesCount: Int = 0,
)

/**
 * Pure parsing, strict Hindi (India) filtering, ranking, and automatic gender organization.
 * Deterministic and unit-testable without requiring an Android device.
 */
object VoiceClassifier {
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    private const val FEATURE_NOT_INSTALLED = "notInstalled"

    /**
     * Strictly accepts Hindi (India), locale `hi-IN`.
     * Rejects English (`en-IN`, `en-US`, etc.) and any other locales.
     */
    fun isHindiIndiaLocale(locale: Locale?): Boolean {
        if (locale == null) return false
        val lang = locale.language.lowercase(Locale.ROOT)
        val country = locale.country.uppercase(Locale.ROOT)
        if (lang != "hi" || country != "IN") return false
        val tag = locale.toLanguageTag().lowercase(Locale.ROOT)
        return tag == "hi-in" || tag.startsWith("hi-in-")
    }

    /** Alias locked strictly to Hindi (India). */
    fun isSupportedLanguage(locale: Locale): Boolean = isHindiIndiaLocale(locale)

    /**
     * Strictly validates a [BundledHindiVoice]'s gender, locale, model/tokens/config paths,
     * and sample rate before synthesis so mismatched or broken metadata fails explicitly
     * instead of playing a wrong fallback voice or crashing native JNI.
     */
    fun validateBundledVoice(voice: BundledHindiVoice): VoiceValidationResult {
        if (voice.id.isBlank()) {
            return VoiceValidationResult(false, "Voice ID cannot be blank.")
        }
        if (voice.displayName.isBlank()) {
            return VoiceValidationResult(false, "Voice display name cannot be blank.")
        }
        if (voice.gender != VoiceGender.Female && voice.gender != VoiceGender.Male) {
            return VoiceValidationResult(false, "${voice.displayName} is missing a verified Female/Male gender.")
        }
        if (!isHindiIndiaLocale(voice.locale)) {
            return VoiceValidationResult(false, "${voice.displayName} is not a Hindi (India) voice.")
        }
        if (!voice.lang.equals("hi", ignoreCase = true)) {
            return VoiceValidationResult(false, "${voice.displayName} has unsupported language tag '${voice.lang}'.")
        }
        if (voice.sampleRate !in 8_000..192_000) {
            return VoiceValidationResult(false, "${voice.displayName} has invalid sample rate ${voice.sampleRate}.")
        }
        if (voice.speakerId < 0) {
            return VoiceValidationResult(false, "${voice.displayName} has invalid speaker ID ${voice.speakerId}.")
        }
        if (!voice.modelAssetPath.endsWith(".onnx")) {
            return VoiceValidationResult(false, "${voice.displayName} is missing an ONNX model path.")
        }
        if (voice.tokensAssetPath.isBlank() || voice.configAssetPath.isBlank()) {
            return VoiceValidationResult(false, "${voice.displayName} is missing required config or token files.")
        }
        when (voice.engineKind) {
            BundledEngineKind.PiperVits -> {
                if (!voice.configAssetPath.endsWith(".json")) {
                    return VoiceValidationResult(false, "${voice.displayName} is missing its .onnx.json config.")
                }
            }
            BundledEngineKind.KokoroMultiLang -> {
                if (voice.voicesAssetPath.isBlank()) {
                    return VoiceValidationResult(false, "${voice.displayName} is missing voices.bin.")
                }
            }
            BundledEngineKind.Supertonic -> {
                val missing = listOf(
                    voice.durationPredictorAssetPath,
                    voice.textEncoderAssetPath,
                    voice.vectorEstimatorAssetPath,
                    voice.vocoderAssetPath,
                    voice.ttsJsonAssetPath,
                    voice.unicodeIndexerAssetPath,
                    voice.voiceStyleAssetPath,
                ).any { it.isBlank() }
                if (missing) {
                    return VoiceValidationResult(false, "${voice.displayName} is missing Supertonic model files.")
                }
            }
        }
        return VoiceValidationResult(true)
    }

    /** Validates a catalog [TtsVoiceInfo] entry for Hindi (India) & explicit gender metadata. */
    fun validateVoiceInfo(voice: TtsVoiceInfo): VoiceValidationResult {
        if (voice.id.isBlank()) return VoiceValidationResult(false, "Voice ID cannot be blank.")
        if (voice.gender != VoiceGender.Female && voice.gender != VoiceGender.Male) {
            return VoiceValidationResult(false, "${voice.displayName} lacks verified gender metadata.")
        }
        if (!isHindiIndiaLocale(voice.locale)) {
            return VoiceValidationResult(false, "${voice.displayName} must use locale hi-IN.")
        }
        return VoiceValidationResult(true)
    }

    /**
     * Automatically identifies gender from reliable voice feature metadata only.
     * Never guesses from voice names or pitch; returns [VoiceGender.Unspecified] if absent.
     */
    fun classifyGender(
        voiceName: String,
        features: Set<String>?,
    ): VoiceGender {
        val normalizedFeatures = features
            ?.asSequence()
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?: emptyList()

        val isFemale = normalizedFeatures.any { token ->
            token == "female" ||
                token.contains("gender=female") ||
                token.contains("gender:female") ||
                token.contains("voicegender=female") ||
                token.contains("voice_gender=female") ||
                token.contains("sex=female")
        }
        if (isFemale) return VoiceGender.Female

        val isMale = normalizedFeatures.any { token ->
            !token.contains("female") && (
                token == "male" ||
                    token.contains("gender=male") ||
                    token.contains("gender:male") ||
                    token.contains("voicegender=male") ||
                    token.contains("voice_gender=male") ||
                    token.contains("sex=male")
                )
        }
        if (isMale) return VoiceGender.Male

        return VoiceGender.Unspecified
    }

    /**
     * Builds the deduplicated, automatically organized Hindi (India) catalog capped at
     * [maxPerCategory] Female voices and [maxPerCategory] Male voices.
     * Voices without reliable gender metadata are excluded rather than guessed or faked.
     * Guaranteed bundled offline neural voices are prioritized at the top.
     */
    fun buildHindiCatalog(
        rawVoices: Collection<RawTtsVoice>,
        bundledVoices: Collection<TtsVoiceInfo> = emptyList(),
        maxPerCategory: Int = HindiVoiceCatalog.TARGET_COUNT_PER_CATEGORY,
    ): HindiVoiceCatalog {
        val comparator = compareByDescending<TtsVoiceInfo> { it.isBundled }
            .thenByDescending { it.isInstalled }
            .thenBy { it.isNetworkRequired }
            .thenByDescending { it.qualityScore }
            .thenBy { it.id }

        val systemHindi = rawVoices
            .asSequence()
            .filter { it.id.isNotBlank() && isHindiIndiaLocale(it.locale) }
            .map { raw ->
                val cleanId = raw.id.trim()
                val gender = classifyGender(cleanId, raw.features)
                val isInstalled = raw.features?.any { it.equals(FEATURE_NOT_INSTALLED, ignoreCase = true) } != true
                TtsVoiceInfo(
                    id = cleanId,
                    displayName = formatDisplayName(gender),
                    gender = gender,
                    locale = raw.locale,
                    isNetworkRequired = raw.isNetworkRequired,
                    qualityScore = raw.qualityScore,
                    isInstalled = isInstalled,
                    isBundled = false,
                )
            }
            .toList()

        // Preserve stable bundled declaration order at top while sorting system voices cleanly
        val orderedBundled = bundledVoices.toList()
        val orderedSystem = systemHindi.sortedWith(comparator)
        val allCombined = (orderedBundled + orderedSystem).distinctBy { it.id }

        val allFemale = allCombined
            .filter { it.gender == VoiceGender.Female }
            .mapIndexed { idx, info ->
                info.copy(displayName = formatDisplayName(VoiceGender.Female, idx + 1))
            }

        val allMale = allCombined
            .filter { it.gender == VoiceGender.Male }
            .mapIndexed { idx, info ->
                info.copy(displayName = formatDisplayName(VoiceGender.Male, idx + 1))
            }

        return HindiVoiceCatalog(
            femaleVoices = allFemale.take(maxPerCategory.coerceAtLeast(0)),
            maleVoices = allMale.take(maxPerCategory.coerceAtLeast(0)),
            totalFemaleFound = allFemale.size,
            totalMaleFound = allMale.size,
            totalHindiVoicesCount = allCombined.size,
            installedHindiVoicesCount = allCombined.count { it.isInstalled || it.isBundled },
            bundledVoicesCount = orderedBundled.size,
        )
    }

    /** Resolves which voice ID should be treated as effectively selected when persisting or initializing. */
    fun resolveEffectiveVoiceId(
        savedVoiceId: String?,
        catalog: HindiVoiceCatalog,
        preferMale: Boolean = false,
    ): String? {
        val all = catalog.femaleVoices + catalog.maleVoices
        if (all.isEmpty()) return savedVoiceId
        if (!savedVoiceId.isNullOrBlank() && all.any { it.id == savedVoiceId }) {
            return savedVoiceId
        }
        val preferredList = if (preferMale) catalog.maleVoices else catalog.femaleVoices
        return preferredList.firstOrNull { it.isBundled || it.isInstalled }?.id
            ?: all.firstOrNull { it.isBundled || it.isInstalled }?.id
            ?: all.firstOrNull()?.id
    }

    /** Clean, non-technical display name for the user-facing list. */
    fun formatDisplayName(
        gender: VoiceGender,
        indexWithinGroup: Int? = null,
    ): String {
        val base = when (gender) {
            VoiceGender.Female -> "Hindi Female"
            VoiceGender.Male -> "Hindi Male"
            VoiceGender.Unspecified -> "Hindi Voice"
        }
        return if (indexWithinGroup != null && indexWithinGroup > 0) {
            "$base $indexWithinGroup"
        } else {
            base
        }
    }

    /** Localized Hindi preview sentence. */
    fun sampleTextForLocale(locale: Locale): String {
        return when (locale.language.lowercase(Locale.ROOT)) {
            "hi" -> "नमस्ते! मैं आपकी कैसे मदद कर सकता हूँ?"
            else -> "Hello! I am JARVIS. How can I help you today?"
        }
    }
}
