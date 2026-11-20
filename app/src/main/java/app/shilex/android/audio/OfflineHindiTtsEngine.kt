package app.shilex.android.audio

import android.content.Context
import app.shilex.android.Diag
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * In-app offline Hindi neural TTS engine powered by Sherpa-ONNX (Piper VITS int8, Kokoro-82M int8,
 * and Supertonic-3 int8). Extracts the active voice files lazily from APK assets into `filesDir/tts`
 * and always initializes Sherpa-ONNX via `OfflineTts(assetManager = null, config)` (`newFromFile`)
 * so absolute filesystem paths (`espeak-ng-data`, models) never mix with `AAssetManager`.
 */
class OfflineHindiTtsEngine(private val context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    private var activeKey: String? = null
    private var activeTts: OfflineTts? = null

    data class SynthesizedAudio(
        val pcm16: ByteArray,
        val sampleRate: Int,
        val peakAmplitude: Float,
        val rawPeak: Float = peakAmplitude,
    )

    sealed interface SynthesisOutcome {
        data object Success : SynthesisOutcome
        data object Cancelled : SynthesisOutcome
        data class Failed(val userMessage: String) : SynthesisOutcome
    }

    fun isVoiceAvailable(voice: BundledHindiVoice): Boolean =
        VoiceClassifier.validateBundledVoice(voice).isValid &&
            BundledHindiVoices.isModelBundled(appContext, voice)

    /**
     * Synthesizes [text] offline using the selected [voice] and streams 16-bit mono PCM chunks
     * to [onChunk] as each sentence completes, supporting instant cooperative cancellation via [isCancelled].
     * Never throws or aborts; all validation, initialization, filesystem, and JNI failures return [SynthesisOutcome.Failed].
     */
    fun synthesizeStreaming(
        voice: BundledHindiVoice,
        text: String,
        speed: Float = 1.0f,
        isCancelled: () -> Boolean,
        onChunk: (SynthesizedAudio) -> Unit,
    ): SynthesisOutcome {
        val validation = VoiceClassifier.validateBundledVoice(voice)
        if (!validation.isValid) {
            return SynthesisOutcome.Failed(validation.reason)
        }
        if (text.isBlank()) {
            return SynthesisOutcome.Failed("No text was provided to speak.")
        }
        if (isCancelled()) return SynthesisOutcome.Cancelled

        synchronized(lock) {
            val ttsResult = ensureEngineLocked(voice, isCancelled)
            val tts = when (ttsResult) {
                is EngineLoadResult.Loaded -> ttsResult.tts
                is EngineLoadResult.Cancelled -> return SynthesisOutcome.Cancelled
                is EngineLoadResult.Error -> return SynthesisOutcome.Failed(ttsResult.message)
            }
            if (isCancelled()) return SynthesisOutcome.Cancelled

            val cleanSpeed = speed.coerceIn(0.5f, 2.0f)
            val engineRate = runCatching { tts.sampleRate() }.getOrDefault(voice.sampleRate)
                .takeIf { it in 8_000..192_000 } ?: voice.sampleRate

            var totalSamplesEmitted = 0L
            var maxRawPeak = 0f
            val sentences = splitSentencesForLowLatency(text)

            for (sentence in sentences) {
                if (isCancelled()) return SynthesisOutcome.Cancelled
                var sentenceEmitted = false
                val genResult = try {
                    if (voice.engineKind == BundledEngineKind.Supertonic) {
                        val genConfig = GenerationConfig(
                            silenceScale = 0.2f,
                            speed = cleanSpeed,
                            sid = voice.speakerId,
                            referenceAudio = floatArrayOf(),
                            referenceSampleRate = 0,
                            referenceText = "",
                            numSteps = SUPERTONIC_NUM_STEPS,
                            extra = mapOf("lang" to TtsLanguagePolicy.supertonicLanguage(sentence)),
                        )
                        tts.generateWithConfigAndCallback(
                            text = sentence,
                            config = genConfig,
                        ) { samples ->
                            if (isCancelled()) {
                                0
                            } else {
                                if (samples.isNotEmpty()) {
                                    sentenceEmitted = true
                                    totalSamplesEmitted += samples.size
                                    val audio = floatsToPcm16(samples, engineRate)
                                    if (audio.rawPeak > maxRawPeak) maxRawPeak = audio.rawPeak
                                    onChunk(audio)
                                }
                                1
                            }
                        }
                    } else {
                        tts.generateWithCallback(
                            text = sentence,
                            sid = voice.speakerId,
                            speed = cleanSpeed,
                        ) { samples ->
                            if (isCancelled()) {
                                0
                            } else {
                                if (samples.isNotEmpty()) {
                                    sentenceEmitted = true
                                    totalSamplesEmitted += samples.size
                                    val audio = floatsToPcm16(samples, engineRate)
                                    if (audio.rawPeak > maxRawPeak) maxRawPeak = audio.rawPeak
                                    onChunk(audio)
                                }
                                1
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Diag.w("offline-tts: synthesize failed for ${voice.id}: ${t.message}")
                    return if (isCancelled()) {
                        SynthesisOutcome.Cancelled
                    } else {
                        SynthesisOutcome.Failed("Could not synthesize audio for ${voice.displayName}.")
                    }
                }

                if (isCancelled()) return SynthesisOutcome.Cancelled

                // Fallback per sentence in case callback wasn't invoked by this model backend
                val fallbackSamples = runCatching { genResult.samples }.getOrNull()
                if (!sentenceEmitted && fallbackSamples != null && fallbackSamples.isNotEmpty()) {
                    val outRate = runCatching { genResult.sampleRate }.getOrDefault(engineRate)
                        .takeIf { it in 8_000..192_000 } ?: engineRate
                    totalSamplesEmitted += fallbackSamples.size
                    val audio = floatsToPcm16(fallbackSamples, outRate)
                    if (audio.rawPeak > maxRawPeak) maxRawPeak = audio.rawPeak
                    onChunk(audio)
                }
            }

            if (isCancelled()) return SynthesisOutcome.Cancelled
            if (totalSamplesEmitted <= 0L || maxRawPeak <= MIN_AUDIBLE_PEAK) {
                Diag.w("offline-tts: produced empty or silent PCM for ${voice.id} (samples=$totalSamplesEmitted, peak=$maxRawPeak)")
                return SynthesisOutcome.Failed("${voice.displayName} produced no audible speech samples.")
            }
            return SynthesisOutcome.Success
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { activeTts?.free() }
            activeTts = null
            activeKey = null
        }
    }

    private sealed interface EngineLoadResult {
        data class Loaded(val tts: OfflineTts) : EngineLoadResult
        data object Cancelled : EngineLoadResult
        data class Error(val message: String) : EngineLoadResult
    }

    private fun ensureEngineLocked(
        voice: BundledHindiVoice,
        isCancelled: () -> Boolean,
    ): EngineLoadResult {
        val key = when (voice.engineKind) {
            BundledEngineKind.PiperVits -> "piper:${voice.id}"
            BundledEngineKind.KokoroMultiLang -> "kokoro"
            BundledEngineKind.Supertonic -> "supertonic"
        }
        activeTts?.let { existing ->
            if (activeKey == key) {
                return EngineLoadResult.Loaded(existing)
            }
        }

        // Release any previous model first to keep native RAM bounded on mobile devices
        runCatching { activeTts?.free() }
        activeTts = null
        activeKey = null

        if (isCancelled()) return EngineLoadResult.Cancelled

        val requiredAssetPaths = requiredAssetsFor(voice)
        val minFreeBytes = minRequiredStorageBytes(voice)
        val usableBytes = runCatching { appContext.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (usableBytes < minFreeBytes) {
            return EngineLoadResult.Error("Not enough free storage on this phone to prepare ${voice.displayName}.")
        }

        val espeakDir: File? = if (voice.engineKind != BundledEngineKind.Supertonic) {
            when (val res = ensureEspeakDataDir(isCancelled)) {
                is DirResult.Ready -> res.dir
                is DirResult.Cancelled -> return EngineLoadResult.Cancelled
                is DirResult.Error -> return EngineLoadResult.Error(res.message)
            }
        } else {
            null
        }

        val resolvedPaths = mutableMapOf<String, String>()
        val extractedFiles = mutableMapOf<String, File>()
        for (assetPath in requiredAssetPaths) {
            if (isCancelled()) return EngineLoadResult.Cancelled
            val extracted = extractAssetFileIfNeeded(assetPath, isCancelled)
                ?: return if (isCancelled()) {
                    EngineLoadResult.Cancelled
                } else {
                    EngineLoadResult.Error("Could not extract voice files for ${voice.displayName}.")
                }
            resolvedPaths[assetPath] = extracted.absolutePath
            extractedFiles[assetPath] = extracted
        }

        if (isCancelled()) return EngineLoadResult.Cancelled

        // Pre-validate every native path before calling OfflineTts(null, config), because Sherpa-ONNX
        // C++ calls SHERPA_ONNX_EXIT(-1) (terminating the app process) if any file or espeak table is invalid.
        val integrityError = validateExtractedFiles(voice, extractedFiles, espeakDir)
        if (integrityError != null) {
            Diag.w("offline-tts: pre-init integrity check failed for ${voice.id}: $integrityError")
            invalidateExtractedMarkers(extractedFiles.values, espeakDir)
            return EngineLoadResult.Error(integrityError)
        }

        val config = buildFileConfig(voice, resolvedPaths, espeakDir?.absolutePath.orEmpty())
        val created = try {
            // IMPORTANT: assetManager MUST be null when passing filesystem paths to Sherpa-ONNX,
            // otherwise native JNI calls SHERPA_ONNX_EXIT(-1) on any absolute path.
            OfflineTts(assetManager = null, config = config)
        } catch (t: Throwable) {
            Diag.w("offline-tts: init failed for ${voice.id}: ${t.javaClass.simpleName}: ${t.message}")
            return EngineLoadResult.Error("Could not load ${voice.displayName}: ${t.message ?: "native voice init failed"}")
        }

        if (isCancelled()) {
            runCatching { created.free() }
            return EngineLoadResult.Cancelled
        }

        val loadedRate = runCatching { created.sampleRate() }.getOrDefault(0)
        if (loadedRate !in 8_000..192_000) {
            runCatching { created.free() }
            return EngineLoadResult.Error("${voice.displayName} initialized with an invalid audio rate.")
        }

        activeTts = created
        activeKey = key
        Diag.i("offline-tts: loaded ${voice.id} (${voice.engineKind}) sr=$loadedRate")
        return EngineLoadResult.Loaded(created)
    }

    private fun invalidateExtractedMarkers(files: Collection<File>, espeakDir: File?) {
        files.forEach { file ->
            runCatching { File(file.parentFile, "${file.name}.ready-v1").delete() }
        }
        espeakDir?.let { dir ->
            runCatching { File(dir, ESPEAK_READY_STAMP).delete() }
        }
    }

    private fun requiredAssetsFor(voice: BundledHindiVoice): List<String> = when (voice.engineKind) {
        BundledEngineKind.PiperVits -> listOf(
            voice.modelAssetPath,
            voice.tokensAssetPath,
            voice.configAssetPath,
        )
        BundledEngineKind.KokoroMultiLang -> listOf(
            voice.modelAssetPath,
            voice.voicesAssetPath,
            voice.tokensAssetPath,
        )
        BundledEngineKind.Supertonic -> listOf(
            voice.durationPredictorAssetPath,
            voice.textEncoderAssetPath,
            voice.vectorEstimatorAssetPath,
            voice.vocoderAssetPath,
            voice.ttsJsonAssetPath,
            voice.unicodeIndexerAssetPath,
            voice.voiceStyleAssetPath,
        )
    }

    private fun minRequiredStorageBytes(voice: BundledHindiVoice): Long = when (voice.engineKind) {
        BundledEngineKind.PiperVits -> 32L * 1024L * 1024L
        BundledEngineKind.KokoroMultiLang -> 180L * 1024L * 1024L
        BundledEngineKind.Supertonic -> 200L * 1024L * 1024L
    }

    private fun buildFileConfig(
        voice: BundledHindiVoice,
        paths: Map<String, String>,
        espeakDirPath: String,
    ): OfflineTtsConfig {
        val modelConfig = when (voice.engineKind) {
            BundledEngineKind.PiperVits -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = paths.getValue(voice.modelAssetPath),
                    lexicon = "",
                    tokens = paths.getValue(voice.tokensAssetPath),
                    dataDir = espeakDirPath,
                    dictDir = "",
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            BundledEngineKind.KokoroMultiLang -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = paths.getValue(voice.modelAssetPath),
                    voices = paths.getValue(voice.voicesAssetPath),
                    tokens = paths.getValue(voice.tokensAssetPath),
                    dataDir = espeakDirPath,
                    lexicon = "",
                    lang = voice.lang,
                    dictDir = "",
                    lengthScale = 1.0f,
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            )
            BundledEngineKind.Supertonic -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = paths.getValue(voice.durationPredictorAssetPath),
                    textEncoder = paths.getValue(voice.textEncoderAssetPath),
                    vectorEstimator = paths.getValue(voice.vectorEstimatorAssetPath),
                    vocoder = paths.getValue(voice.vocoderAssetPath),
                    ttsJson = paths.getValue(voice.ttsJsonAssetPath),
                    unicodeIndexer = paths.getValue(voice.unicodeIndexerAssetPath),
                    voiceStyle = paths.getValue(voice.voiceStyleAssetPath),
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = modelConfig)
    }

    private sealed interface DirResult {
        data class Ready(val dir: File) : DirResult
        data object Cancelled : DirResult
        data class Error(val message: String) : DirResult
    }

    private fun ensureEspeakDataDir(isCancelled: () -> Boolean): DirResult {
        val targetDir = File(appContext.filesDir, "tts/espeak-ng-data")
        val stamp = File(targetDir, ESPEAK_READY_STAMP)
        val hasAllRequired = BundledHindiVoices.REQUIRED_ESPEAK_FILES.all { rel ->
            val f = File(targetDir, rel)
            f.exists() && f.isFile && f.length() > 0L
        }
        if (stamp.exists() && hasAllRequired) {
            return DirResult.Ready(targetDir)
        }
        return try {
            if (stamp.exists()) stamp.delete()
            val copied = copyAssetDir(BundledHindiVoices.ESPEAK_DATA_ASSET_DIR, targetDir, isCancelled)
            if (!copied || isCancelled()) {
                DirResult.Cancelled
            } else {
                val missingAfter = BundledHindiVoices.REQUIRED_ESPEAK_FILES.firstOrNull { rel ->
                    val f = File(targetDir, rel)
                    !f.exists() || !f.isFile || f.length() <= 0L
                }
                if (missingAfter != null) {
                    DirResult.Error("Hindi phonemizer table '$missingAfter' is missing or incomplete.")
                } else {
                    stamp.writeText("ok")
                    DirResult.Ready(targetDir)
                }
            }
        } catch (t: Throwable) {
            Diag.w("offline-tts: failed copying espeak-ng-data: ${t.message}")
            DirResult.Error("Could not prepare Hindi phonemizer data: ${t.message ?: "I/O error"}")
        }
    }

    private fun copyAssetDir(
        assetPath: String,
        destDir: File,
        isCancelled: () -> Boolean,
    ): Boolean {
        if (isCancelled()) return false
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            return copyAssetAtomic(assetPath, destDir, isCancelled)
        }
        if (!destDir.exists() && !destDir.mkdirs() && !destDir.isDirectory) {
            throw IOException("Cannot create directory ${destDir.absolutePath}")
        }
        for (child in children) {
            if (!copyAssetDir("$assetPath/$child", File(destDir, child), isCancelled)) {
                return false
            }
        }
        return true
    }

    private fun extractAssetFileIfNeeded(
        assetPath: String,
        isCancelled: () -> Boolean,
    ): File? {
        val destFile = File(appContext.filesDir, "tts/$assetPath")
        val readyMarker = File(destFile.parentFile, "${destFile.name}.ready-v1")
        if (destFile.exists() && destFile.length() > 0L && readyMarker.exists()) {
            return destFile
        }
        return try {
            if (readyMarker.exists()) readyMarker.delete()
            if (!copyAssetAtomic(assetPath, destFile, isCancelled) || isCancelled()) {
                null
            } else {
                readyMarker.writeText("ok")
                destFile
            }
        } catch (t: Throwable) {
            Diag.w("offline-tts: failed extracting $assetPath: ${t.message}")
            null
        }
    }

    private fun copyAssetAtomic(
        assetPath: String,
        destFile: File,
        isCancelled: () -> Boolean,
    ): Boolean {
        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()
        val buf = ByteArray(64 * 1024)
        var completed = false
        try {
            appContext.assets.open(assetPath).use { input ->
                tmpFile.outputStream().use { output ->
                    while (true) {
                        if (isCancelled()) return false
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            if (tmpFile.length() <= 0L) {
                throw IOException("Empty asset extracted: $assetPath")
            }
            if (destFile.exists() && !destFile.delete()) {
                throw IOException("Could not replace ${destFile.absolutePath}")
            }
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            completed = true
            return true
        } finally {
            if (!completed && tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    internal companion object {
        private const val SUPERTONIC_NUM_STEPS = 5
        private const val MIN_AUDIBLE_PEAK = 1e-6f
        private const val ESPEAK_READY_STAMP = ".ready-v3"

        /**
         * Verifies that every required native model, tokens, config, and espeak-ng-data file exists
         * cleanly on disk before invoking JNI `OfflineTts` (preventing native `SHERPA_ONNX_EXIT` aborts).
         */
        internal fun validateExtractedFiles(
            voice: BundledHindiVoice,
            paths: Map<String, File>,
            espeakDir: File?,
        ): String? {
            val required = when (voice.engineKind) {
                BundledEngineKind.PiperVits -> listOf(
                    voice.modelAssetPath,
                    voice.tokensAssetPath,
                )
                BundledEngineKind.KokoroMultiLang -> listOf(
                    voice.modelAssetPath,
                    voice.voicesAssetPath,
                    voice.tokensAssetPath,
                )
                BundledEngineKind.Supertonic -> listOf(
                    voice.durationPredictorAssetPath,
                    voice.textEncoderAssetPath,
                    voice.vectorEstimatorAssetPath,
                    voice.vocoderAssetPath,
                    voice.ttsJsonAssetPath,
                    voice.unicodeIndexerAssetPath,
                    voice.voiceStyleAssetPath,
                )
            }
            for (assetPath in required) {
                val f = paths[assetPath]
                if (f == null || !f.exists() || !f.isFile || f.length() <= 0L) {
                    return "Voice file '$assetPath' is missing or empty for ${voice.displayName}."
                }
            }
            if (voice.engineKind != BundledEngineKind.Supertonic) {
                if (espeakDir == null || !espeakDir.exists() || !espeakDir.isDirectory) {
                    return "Hindi phonemizer directory is missing for ${voice.displayName}."
                }
                for (rel in BundledHindiVoices.REQUIRED_ESPEAK_FILES) {
                    val f = File(espeakDir, rel)
                    if (!f.exists() || !f.isFile || f.length() <= 0L) {
                        return "Hindi phonemizer file '$rel' is missing or corrupt."
                    }
                }
            } else {
                val indexer = paths[voice.unicodeIndexerAssetPath]
                if (indexer == null || indexer.length() % 4L != 0L) {
                    return "Invalid unicode indexer file for ${voice.displayName}."
                }
                val style = paths[voice.voiceStyleAssetPath]
                if (style == null || style.length() < 32L) {
                    return "Invalid voice style file for ${voice.displayName}."
                }
            }
            return null
        }

        internal fun floatsToPcm16(samples: FloatArray, sampleRate: Int): SynthesizedAudio {
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var peak = 0f
            for (s in samples) {
                val finite = if (s.isFinite()) s else 0f
                val clamped = finite.coerceIn(-1f, 1f)
                val a = abs(clamped)
                if (a > peak) peak = a
                val pcmVal = (clamped * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                buf.putShort(pcmVal)
            }
            return SynthesizedAudio(
                pcm16 = buf.array(),
                sampleRate = sampleRate,
                peakAmplitude = peak.coerceIn(0.12f, 1f),
                rawPeak = peak,
            )
        }

        internal fun splitSentencesForLowLatency(text: String): List<String> {
            val rawParts = text.trim()
                .split(Regex("(?<=[।.!?\\n])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return if (rawParts.isEmpty()) listOf(text.trim()) else rawParts
        }
    }
}
