package app.shilex.android.audio

import app.shilex.android.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceClassifierTest {

    @Test
    fun filtersStrictlyToHindiIndiaLocaleAndRejectsEnglish() {
        assertTrue(VoiceClassifier.isHindiIndiaLocale(Locale("hi", "IN")))
        assertTrue(VoiceClassifier.isSupportedLanguage(Locale("hi", "IN")))

        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale("en", "IN")))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale.US))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale.UK))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale("hi", "US")))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale("hi")))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale.JAPANESE))
    }

    @Test
    fun classifiesGenderAutomaticallyFromReliableMetadataNeverFromNames() {
        assertEquals(
            VoiceGender.Female,
            VoiceClassifier.classifyGender("hi-in-x-hia-local", setOf("gender=female")),
        )
        assertEquals(
            VoiceGender.Male,
            VoiceClassifier.classifyGender("hi-in-x-hid-local", setOf("gender=male")),
        )
        assertEquals(
            VoiceGender.Female,
            VoiceClassifier.classifyGender("hi-in-x-hif-network", setOf("female")),
        )

        // Never guesses from opaque or suggestive voice names when metadata is absent
        assertEquals(
            VoiceGender.Unspecified,
            VoiceClassifier.classifyGender("hi-in-x-hia-local", setOf("networkTts")),
        )
        assertEquals(
            VoiceGender.Unspecified,
            VoiceClassifier.classifyGender("hi-in-standard-female-name-only", emptySet()),
        )
    }

    @Test
    fun buildsDeduplicatedFivePlusFiveHindiCatalogWithCleanNonTechnicalNames() {
        val raw = mutableListOf<RawTtsVoice>()
        // English voices must never appear
        raw += RawTtsVoice("en-in-x-end-local", Locale("en", "IN"), isNetworkRequired = false, qualityScore = 500, features = setOf("gender=female"))
        raw += RawTtsVoice("en-us-x-sfg-local", Locale.US, isNetworkRequired = false, qualityScore = 500, features = setOf("gender=male"))

        // 7 female hi-IN voices (with 1 duplicate ID, 1 online, 1 not installed) -> capped at top 5
        raw += RawTtsVoice("hi-in-f-low", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 200, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-not-installed", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 500, features = setOf("gender=female", "notInstalled"))
        raw += RawTtsVoice("hi-in-f-online", Locale("hi", "IN"), isNetworkRequired = true, qualityScore = 500, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-1", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 500, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-1", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=female")) // duplicate ID
        raw += RawTtsVoice("hi-in-f-2", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-3", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 350, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-4", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 300, features = setOf("gender=female"))

        // Only 2 male hi-IN voices -> shows 2 honestly
        raw += RawTtsVoice("hi-in-m-1", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=male"))
        raw += RawTtsVoice("hi-in-m-2", Locale("hi", "IN"), isNetworkRequired = true, qualityScore = 300, features = setOf("gender=male"))

        // Unclassified hi-IN voice (no gender metadata) -> excluded cleanly without guessing or inventing
        raw += RawTtsVoice("hi-in-x-hia-local", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = emptySet())

        val catalog = VoiceClassifier.buildHindiCatalog(raw)

        // All voices strictly hi-IN
        assertTrue((catalog.femaleVoices + catalog.maleVoices).all { it.locale == Locale("hi", "IN") })
        assertFalse((catalog.femaleVoices + catalog.maleVoices).any { it.id.startsWith("en-") })

        // Capped at 5 Female, exact 2 Male
        assertEquals(5, catalog.femaleVoices.size)
        assertEquals(7, catalog.totalFemaleFound)
        assertEquals(2, catalog.maleVoices.size)
        assertEquals(2, catalog.totalMaleFound)

        // Display names are clean and free of voice IDs or technical jargon
        assertEquals("Hindi Female 1", catalog.femaleVoices[0].displayName)
        assertEquals("Hindi Female 5", catalog.femaleVoices[4].displayName)
        assertEquals("Hindi Male 1", catalog.maleVoices[0].displayName)
        assertEquals("Hindi Male 2", catalog.maleVoices[1].displayName)

        // Ranking: installed + offline + highest quality first
        assertEquals("hi-in-f-1", catalog.femaleVoices[0].id)
        assertEquals("hi-in-f-2", catalog.femaleVoices[1].id)
        assertEquals("hi-in-f-3", catalog.femaleVoices[2].id)
        assertEquals("hi-in-f-4", catalog.femaleVoices[3].id)
        assertEquals("hi-in-f-low", catalog.femaleVoices[4].id)
    }

    @Test
    fun showsEmptyListsWithoutFabricatingWhenEngineHasNoGenderedHindiVoices() {
        val raw = listOf(
            RawTtsVoice("en-in-x-end-local", Locale("en", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=female")),
            RawTtsVoice("hi-in-x-hia-local", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = emptySet()),
        )
        val catalog = VoiceClassifier.buildHindiCatalog(raw)
        assertEquals(0, catalog.femaleVoices.size)
        assertEquals(0, catalog.maleVoices.size)
        assertEquals(1, catalog.totalHindiVoicesCount)
    }

    @Test
    fun settingsFilterAndHindiPreviewSampleWorkCorrectly() {
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter("ALL"))
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter(null))
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter("FEMALE"))
        assertEquals(Settings.GENDER_MALE, Settings.normalizeGenderFilter("male"))

        val hiSample = VoiceClassifier.sampleTextForLocale(Locale("hi", "IN"))
        assertEquals("नमस्ते! मैं आपकी कैसे मदद कर सकता हूँ?", hiSample)
        assertEquals(
            VoiceClassifier.HINDI_PREVIEW_SAMPLE,
            VoiceClassifier.previewSampleText(PreviewLanguage.HINDI),
        )
        assertEquals(
            VoiceClassifier.ENGLISH_PREVIEW_SAMPLE,
            VoiceClassifier.previewSampleText(PreviewLanguage.ENGLISH),
        )
    }

    @Test
    fun bundledHindiVoicesAreGenuineDistinctAndOfflineCapable() {
        val voices = BundledHindiVoices.ALL
        assertEquals(10, voices.size)
        val female = voices.filter { it.gender == VoiceGender.Female }
        assertEquals(5, female.size)
        assertEquals((0..4).toList(), female.map { it.speakerId })
        assertEquals((1..5).map { "Hindi Female $it" }, female.map { it.displayName })

        val male = voices.filter { it.gender == VoiceGender.Male }
        assertEquals(5, male.size)
        assertEquals((5..9).toList(), male.map { it.speakerId })
        assertEquals((1..5).map { "Hindi Male $it" }, male.map { it.displayName })

        assertEquals(10, voices.map { it.id }.toSet().size)
        assertTrue(voices.all { it.locale == Locale("hi", "IN") })
        assertTrue(voices.all { it.toTtsVoiceInfo().isOfflineCapable })
        assertTrue(voices.all { it.engineKind == BundledEngineKind.Supertonic })
        assertTrue(voices.all { it.sampleRate == 24000 })
        assertTrue(voices.all { it.licenseNotice == "OpenRAIL-M" })
        assertTrue(voices.all { it.datasetSource.contains("Supertonic-3") })
        assertTrue(
            voices.all {
                it.modelAssetPath.endsWith(".onnx") &&
                    it.tokensAssetPath.endsWith(".bin") &&
                    it.voiceStyleAssetPath.endsWith("voice.bin")
            },
        )
        assertTrue(BundledHindiVoices.validateCatalog().isValid)
    }

    @Test
    fun buildCatalogPrioritizesBundledVoicesAndHonorsFiveVoiceCap() {
        val bundled = BundledHindiVoices.ALL.map { it.toTtsVoiceInfo() }
        val catalog = VoiceClassifier.buildHindiCatalog(emptyList(), bundled)

        assertEquals(5, catalog.femaleVoices.size)
        assertEquals("Hindi Female 1", catalog.femaleVoices[0].displayName)
        assertEquals("Hindi Female 2", catalog.femaleVoices[1].displayName)
        assertEquals("Hindi Female 3", catalog.femaleVoices[2].displayName)
        assertEquals("Hindi Female 4", catalog.femaleVoices[3].displayName)
        assertEquals("Hindi Female 5", catalog.femaleVoices[4].displayName)
        assertEquals("bundled_hi_female_supertonic_f1", catalog.femaleVoices[0].id)
        assertEquals("bundled_hi_female_supertonic_f5", catalog.femaleVoices[4].id)
        assertTrue(catalog.femaleVoices.all { it.isBundled && it.isOfflineCapable })

        assertEquals(5, catalog.maleVoices.size)
        assertEquals("Hindi Male 1", catalog.maleVoices[0].displayName)
        assertEquals("Hindi Male 2", catalog.maleVoices[1].displayName)
        assertEquals("Hindi Male 3", catalog.maleVoices[2].displayName)
        assertEquals("Hindi Male 4", catalog.maleVoices[3].displayName)
        assertEquals("Hindi Male 5", catalog.maleVoices[4].displayName)
        assertEquals("bundled_hi_male_supertonic_m1", catalog.maleVoices[0].id)
        assertEquals("bundled_hi_male_supertonic_m5", catalog.maleVoices[4].id)
        assertTrue(catalog.maleVoices.all { it.isBundled && it.isOfflineCapable })

        // Verify 5-per-category cap holds when combined with system voices, keeping bundled first
        val extraSystem = (1..4).flatMap { i ->
            listOf(
                RawTtsVoice("sys_hi_f_$i", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=female")),
                RawTtsVoice("sys_hi_m_$i", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=male")),
            )
        }
        val cappedCatalog = VoiceClassifier.buildHindiCatalog(extraSystem, bundled)
        assertEquals(5, cappedCatalog.femaleVoices.size)
        assertEquals(5, cappedCatalog.maleVoices.size)
        assertEquals("bundled_hi_female_supertonic_f1", cappedCatalog.femaleVoices[0].id)
        assertEquals("bundled_hi_male_supertonic_m1", cappedCatalog.maleVoices[0].id)
    }

    @Test
    fun offlineSynthesisIsIntegratedAndEffectiveVoiceSelectionPersistsAcrossDefaults() {
        assertTrue(BundledHindiVoices.IS_OFFLINE_SYNTHESIS_INTEGRATED)
        assertTrue(BundledHindiVoices.availableForRuntime(null).isEmpty())

        val catalog = VoiceClassifier.buildHindiCatalog(emptyList(), BundledHindiVoices.ALL.map { it.toTtsVoiceInfo() })

        // Default female / male fallback when no saved voice ID exists
        assertEquals(
            "bundled_hi_female_supertonic_f1",
            VoiceClassifier.resolveEffectiveVoiceId(null, catalog, preferMale = false),
        )
        assertEquals(
            "bundled_hi_male_supertonic_m1",
            VoiceClassifier.resolveEffectiveVoiceId(null, catalog, preferMale = true),
        )

        // Preserves user's saved selection exactly (including Supertonic voices)
        assertEquals(
            "bundled_hi_male_supertonic_m4",
            VoiceClassifier.resolveEffectiveVoiceId("bundled_hi_male_kokoro_psi", catalog, preferMale = false),
        )
        assertEquals(
            "bundled_hi_female_supertonic_f2",
            VoiceClassifier.resolveEffectiveVoiceId("bundled_hi_female_supertonic_f2", catalog, preferMale = true),
        )
        assertEquals(
            "bundled_hi_male_supertonic_m1",
            VoiceClassifier.resolveEffectiveVoiceId("bundled_hi_male_supertonic_m1", catalog, preferMale = false),
        )

        // Missing/removed voice falls back cleanly to gender preference
        assertEquals(
            "bundled_hi_male_supertonic_m1",
            VoiceClassifier.resolveEffectiveVoiceId("removed_voice_id", catalog, preferMale = true),
        )
    }

    @Test
    fun legacySavedVoiceIdsMigrateToHindiCapableSupertonicStyles() {
        val catalog = VoiceClassifier.buildHindiCatalog(emptyList(), BundledHindiVoices.ALL.map { it.toTtsVoiceInfo() })
        val migrations = mapOf(
            "bundled_hi_female_priyamvada" to "bundled_hi_female_supertonic_f1",
            "bundled_hi_female_kokoro_alpha" to "bundled_hi_female_supertonic_f2",
            "bundled_hi_female_kokoro_beta" to "bundled_hi_female_supertonic_f3",
            "bundled_hi_male_pratham" to "bundled_hi_male_supertonic_m1",
            "bundled_hi_male_rohan" to "bundled_hi_male_supertonic_m2",
            "bundled_hi_male_kokoro_omega" to "bundled_hi_male_supertonic_m3",
            "bundled_hi_male_kokoro_psi" to "bundled_hi_male_supertonic_m4",
        )
        migrations.forEach { (oldId, newId) ->
            assertEquals(newId, VoiceClassifier.resolveEffectiveVoiceId(oldId, catalog))
        }
    }

    @Test
    fun offlineEnginePcmConversionSanitizesNonFiniteAndSplitsHindiSentences() {
        val converted = OfflineHindiTtsEngine.floatsToPcm16(
            samples = floatArrayOf(0f, 0.5f, -1.5f, Float.NaN, Float.POSITIVE_INFINITY),
            sampleRate = 44100,
        )
        assertEquals(10, converted.pcm16.size)
        assertEquals(44100, converted.sampleRate)
        assertEquals(1.0f, converted.rawPeak, 1e-4f)

        val silent = OfflineHindiTtsEngine.floatsToPcm16(
            samples = floatArrayOf(0f, Float.NaN),
            sampleRate = 22050,
        )
        assertEquals(0f, silent.rawPeak, 1e-6f)

        val parts = OfflineHindiTtsEngine.splitSentencesForLowLatency(
            "नमस्ते! मैं जार्विस हूँ। बताइए, मैं आपकी क्या मदद कर सकता हूँ?",
        )
        assertEquals(3, parts.size)

        val previewParts = OfflineHindiTtsEngine.splitSentencesForLowLatency(
            "नमस्ते! मैं आपकी कैसे मदद कर सकता हूँ?",
        )
        assertEquals(2, previewParts.size)
        assertEquals("नमस्ते!", previewParts[0])
        assertEquals("मैं आपकी कैसे मदद कर सकता हूँ?", previewParts[1])
    }

    @Test
    fun rejectsInvalidVoiceMetadataAndMissingSupertonicFilesBeforeNativeJniCall() {
        val catalogValidation = BundledHindiVoices.validateCatalog()
        assertTrue(catalogValidation.isValid)
        assertEquals(10, BundledHindiVoices.ALL.size)

        val badVoice = BundledHindiVoices.PRIYAMVADA.copy(
            locale = java.util.Locale.US,
            sampleRate = 0,
        )
        assertFalse(VoiceClassifier.validateBundledVoice(badVoice).isValid)
        assertFalse(VoiceClassifier.validateVoiceInfo(badVoice.toTtsVoiceInfo()).isValid)
        assertTrue(VoiceClassifier.validateBundledVoice(BundledHindiVoices.PRIYAMVADA).isValid)

        val tempRoot = java.nio.file.Files.createTempDirectory("tts_guard_test").toFile()
        try {
            val voice = BundledHindiVoices.SUPERTONIC_F1
            val paths = listOf(
                voice.durationPredictorAssetPath,
                voice.textEncoderAssetPath,
                voice.vectorEstimatorAssetPath,
                voice.vocoderAssetPath,
                voice.ttsJsonAssetPath,
                voice.unicodeIndexerAssetPath,
                voice.voiceStyleAssetPath,
            ).distinct().associateWith { assetPath ->
                val file = java.io.File(tempRoot, assetPath.substringAfterLast('/'))
                val size = if (assetPath == voice.voiceStyleAssetPath) 64 else 8
                file.writeBytes(ByteArray(size) { 1 })
                file
            }

            val missingModel = OfflineHindiTtsEngine.validateExtractedFiles(
                voice = voice,
                paths = paths - voice.durationPredictorAssetPath,
                espeakDir = null,
            )
            assertTrue(missingModel?.contains("duration_predictor") == true)

            // Supertonic uses its own Unicode index and voice style assets; no eSpeak tree is needed.
            val valid = OfflineHindiTtsEngine.validateExtractedFiles(voice, paths, espeakDir = null)
            assertEquals(null, valid)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
