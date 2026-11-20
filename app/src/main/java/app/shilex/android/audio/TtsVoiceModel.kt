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
 * Ten bundled Supertonic-3 Hindi/English voices. The style IDs are distinct trained styles:
 * female F1-F5 use speaker IDs 0-4; male M1-M5 use speaker IDs 5-9.
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

    private fun supertonicVoice(style: String, gender: VoiceGender, speakerId: Int): BundledHindiVoice =
        BundledHindiVoice(
            id = "bundled_hi_${gender.label.lowercase()}_supertonic_${style.lowercase()}",
            displayName = "Hindi ${gender.label} ${if (gender == VoiceGender.Female) speakerId + 1 else speakerId - 4}",
            gender = gender,
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
            speakerId = speakerId,
            lang = "hi",
            sampleRate = 24000,
            licenseNotice = "OpenRAIL-M",
            datasetSource = "Supertone Supertonic-3 multilingual ${gender.label} style $style (sid $speakerId)",
        )

    val SUPERTONIC_F1 = supertonicVoice("F1", VoiceGender.Female, 0)
    val SUPERTONIC_F2 = supertonicVoice("F2", VoiceGender.Female, 1)
    val SUPERTONIC_F3 = supertonicVoice("F3", VoiceGender.Female, 2)
    val SUPERTONIC_F4 = supertonicVoice("F4", VoiceGender.Female, 3)
    val SUPERTONIC_F5 = supertonicVoice("F5", VoiceGender.Female, 4)
    val SUPERTONIC_M1 = supertonicVoice("M1", VoiceGender.Male, 5)
    val SUPERTONIC_M2 = supertonicVoice("M2", VoiceGender.Male, 6)
    val SUPERTONIC_M3 = supertonicVoice("M3", VoiceGender.Male, 7)
    val SUPERTONIC_M4 = supertonicVoice("M4", VoiceGender.Male, 8)
    val SUPERTONIC_M5 = supertonicVoice("M5", VoiceGender.Male, 9)

    // Preserve source compatibility for callers of the former catalog constants.
    val PRIYAMVADA get() = SUPERTONIC_F1
    val HF_ALPHA get() = SUPERTONIC_F2
    val HF_BETA get() = SUPERTONIC_F3
    val PRATHAM get() = SUPERTONIC_M1
    val ROHAN get() = SUPERTONIC_M2
    val HM_OMEGA get() = SUPERTONIC_M3
    val HM_PSI get() = SUPERTONIC_M4

    val ALL: List<BundledHindiVoice> = listOf(
        SUPERTONIC_F1, SUPERTONIC_F2, SUPERTONIC_F3, SUPERTONIC_F4, SUPERTONIC_F5,
        SUPERTONIC_M1, SUPERTONIC_M2, SUPERTONIC_M3, SUPERTONIC_M4, SUPERTONIC_M5,
    )

    /** True: in-app Sherpa-ONNX offline neural TTS runtime is integrated and reads bundled APK assets. */
    const val IS_OFFLINE_SYNTHESIS_INTEGRATED = true

    private val LEGACY_VOICE_IDS = mapOf(
        "bundled_hi_female_priyamvada" to SUPERTONIC_F1,
        "bundled_hi_female_kokoro_alpha" to SUPERTONIC_F2,
        "bundled_hi_female_kokoro_beta" to SUPERTONIC_F3,
        "bundled_hi_male_pratham" to SUPERTONIC_M1,
        "bundled_hi_male_rohan" to SUPERTONIC_M2,
        "bundled_hi_male_kokoro_omega" to SUPERTONIC_M3,
        "bundled_hi_male_kokoro_psi" to SUPERTONIC_M4,
    )

    fun find(voiceId: String?): BundledHindiVoice? =
        ALL.firstOrNull { it.id == voiceId } ?: LEGACY_VOICE_IDS[voiceId]

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
        val migratedVoiceId = BundledHindiVoices.find(savedVoiceId)?.id ?: savedVoiceId
        if (!migratedVoiceId.isNullOrBlank() && all.any { it.id == migratedVoiceId }) {
            return migratedVoiceId
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
