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
    }

    @Test
    fun bundledHindiVoicesAreGenuineDistinctAndOfflineCapable() {
        val voices = BundledHindiVoices.ALL
        assertEquals(10, voices.size)

        // 5 distinct Female voices: Priyamvada (Piper), hf_alpha & hf_beta (Kokoro-82M), F1 & F2 (Supertonic-3)
        val female = voices.filter { it.gender == VoiceGender.Female }
        assertEquals(5, female.size)
        assertEquals("bundled_hi_female_priyamvada", female[0].id)
        assertEquals("Hindi Female 1", female[0].displayName)
        assertEquals(BundledEngineKind.PiperVits, female[0].engineKind)
        assertTrue(female[0].modelAssetPath.endsWith(".onnx"))
        assertTrue(female[0].configAssetPath.endsWith(".json"))
        assertEquals(22050, female[0].sampleRate)
        assertTrue(female[0].licenseNotice.isNotBlank())
        assertTrue(female[0].datasetSource.contains("AI4Bharat"))

        assertEquals("bundled_hi_female_kokoro_alpha", female[1].id)
        assertEquals("Hindi Female 2", female[1].displayName)
        assertEquals(BundledEngineKind.KokoroMultiLang, female[1].engineKind)
        assertEquals(31, female[1].speakerId)
        assertEquals("voices/kokoro/voices.bin", female[1].voicesAssetPath)
        assertEquals(24000, female[1].sampleRate)

        assertEquals("bundled_hi_female_kokoro_beta", female[2].id)
        assertEquals("Hindi Female 3", female[2].displayName)
        assertEquals(BundledEngineKind.KokoroMultiLang, female[2].engineKind)
        assertEquals(32, female[2].speakerId)

        assertEquals("bundled_hi_female_supertonic_f1", female[3].id)
        assertEquals("Hindi Female 4", female[3].displayName)
        assertEquals(BundledEngineKind.Supertonic, female[3].engineKind)
        assertEquals(0, female[3].speakerId)
        assertEquals(44100, female[3].sampleRate)
        assertEquals("MIT", female[3].licenseNotice)
        assertTrue(female[3].durationPredictorAssetPath.endsWith(".onnx"))
        assertTrue(female[3].voiceStyleAssetPath.endsWith("voice.bin"))

        assertEquals("bundled_hi_female_supertonic_f2", female[4].id)
        assertEquals("Hindi Female 5", female[4].displayName)
        assertEquals(BundledEngineKind.Supertonic, female[4].engineKind)
        assertEquals(1, female[4].speakerId)
        assertEquals(44100, female[4].sampleRate)
        assertEquals("MIT", female[4].licenseNotice)

        // 5 distinct Male voices: Pratham (Piper), Rohan (Piper), hm_omega & hm_psi (Kokoro-82M), M1 (Supertonic-3)
        val male = voices.filter { it.gender == VoiceGender.Male }
        assertEquals(5, male.size)
        assertEquals("bundled_hi_male_pratham", male[0].id)
        assertEquals("Hindi Male 1", male[0].displayName)
        assertEquals(BundledEngineKind.PiperVits, male[0].engineKind)

        assertEquals("bundled_hi_male_rohan", male[1].id)
        assertEquals("Hindi Male 2", male[1].displayName)
        assertEquals(BundledEngineKind.PiperVits, male[1].engineKind)

        assertEquals("bundled_hi_male_kokoro_omega", male[2].id)
        assertEquals("Hindi Male 3", male[2].displayName)
        assertEquals(BundledEngineKind.KokoroMultiLang, male[2].engineKind)
        assertEquals(33, male[2].speakerId)

        assertEquals("bundled_hi_male_kokoro_psi", male[3].id)
        assertEquals("Hindi Male 4", male[3].displayName)
        assertEquals(BundledEngineKind.KokoroMultiLang, male[3].engineKind)
        assertEquals(34, male[3].speakerId)

        assertEquals("bundled_hi_male_supertonic_m1", male[4].id)
        assertEquals("Hindi Male 5", male[4].displayName)
        assertEquals(BundledEngineKind.Supertonic, male[4].engineKind)
        assertEquals(5, male[4].speakerId)
        assertEquals(44100, male[4].sampleRate)
        assertEquals("MIT", male[4].licenseNotice)

        assertEquals(10, voices.map { it.id }.toSet().size)
        assertTrue(voices.all { it.locale == Locale("hi", "IN") })
        assertTrue(voices.all { it.toTtsVoiceInfo().isOfflineCapable })
        assertTrue(
            voices.all {
                it.modelAssetPath.endsWith(".onnx") &&
                    (it.tokensAssetPath.endsWith("tokens.txt") || it.tokensAssetPath.endsWith(".bin"))
            },
        )
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
        assertEquals("bundled_hi_female_priyamvada", catalog.femaleVoices[0].id)
        assertEquals("bundled_hi_female_supertonic_f2", catalog.femaleVoices[4].id)
        assertTrue(catalog.femaleVoices.all { it.isBundled && it.isOfflineCapable })

        assertEquals(5, catalog.maleVoices.size)
        assertEquals("Hindi Male 1", catalog.maleVoices[0].displayName)
        assertEquals("Hindi Male 2", catalog.maleVoices[1].displayName)
        assertEquals("Hindi Male 3", catalog.maleVoices[2].displayName)
        assertEquals("Hindi Male 4", catalog.maleVoices[3].displayName)
        assertEquals("Hindi Male 5", catalog.maleVoices[4].displayName)
        assertEquals("bundled_hi_male_pratham", catalog.maleVoices[0].id)
        assertEquals("bundled_hi_male_supertonic_m1", catalog.maleVoices[4].id)
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
        assertEquals("bundled_hi_female_priyamvada", cappedCatalog.femaleVoices[0].id)
        assertEquals("bundled_hi_male_pratham", cappedCatalog.maleVoices[0].id)
    }

    @Test
    fun offlineSynthesisIsIntegratedAndEffectiveVoiceSelectionPersistsAcrossDefaults() {
        assertTrue(BundledHindiVoices.IS_OFFLINE_SYNTHESIS_INTEGRATED)
        assertTrue(BundledHindiVoices.availableForRuntime(null).isEmpty())

        val catalog = VoiceClassifier.buildHindiCatalog(emptyList(), BundledHindiVoices.ALL.map { it.toTtsVoiceInfo() })

        // Default female / male fallback when no saved voice ID exists
        assertEquals(
            "bundled_hi_female_priyamvada",
            VoiceClassifier.resolveEffectiveVoiceId(null, catalog, preferMale = false),
        )
        assertEquals(
            "bundled_hi_male_pratham",
            VoiceClassifier.resolveEffectiveVoiceId(null, catalog, preferMale = true),
        )

        // Preserves user's saved selection exactly (including Supertonic voices)
        assertEquals(
            "bundled_hi_male_kokoro_psi",
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
            "bundled_hi_male_pratham",
            VoiceClassifier.resolveEffectiveVoiceId("removed_voice_id", catalog, preferMale = true),
        )
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
    fun rejectsInvalidVoiceMetadataAndMissingEspeakTablesBeforeNativeJniCall() {
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
            val espeakDir = java.io.File(tempRoot, "espeak-ng-data").apply { mkdirs() }
            val modelFile = java.io.File(tempRoot, "model.onnx").apply { writeBytes(ByteArray(32) { 1 }) }
            val tokensFile = java.io.File(tempRoot, "tokens.txt").apply { writeText("a 1\n") }

            // Missing espeak tables -> must return failure message instead of invoking Sherpa-ONNX JNI
            espeakDir.resolve("hi_dict").writeBytes(ByteArray(8) { 1 })
            espeakDir.resolve("phontab").writeBytes(ByteArray(8) { 1 })
            val missingTables = OfflineHindiTtsEngine.validateExtractedFiles(
                voice = BundledHindiVoices.PRIYAMVADA,
                paths = mapOf(
                    BundledHindiVoices.PRIYAMVADA.modelAssetPath to modelFile,
                    BundledHindiVoices.PRIYAMVADA.tokensAssetPath to tokensFile,
                ),
                espeakDir = espeakDir,
            )
            assertTrue(missingTables?.contains("phonindex") == true || missingTables?.contains("phondata") == true)

            // Populate all required espeak-ng tables -> validation succeeds cleanly
            espeakDir.resolve("lang/inc").mkdirs()
            BundledHindiVoices.REQUIRED_ESPEAK_FILES.forEach { rel ->
                espeakDir.resolve(rel).writeBytes(ByteArray(8) { 1 })
            }
            val ok = OfflineHindiTtsEngine.validateExtractedFiles(
                voice = BundledHindiVoices.PRIYAMVADA,
                paths = mapOf(
                    BundledHindiVoices.PRIYAMVADA.modelAssetPath to modelFile,
                    BundledHindiVoices.PRIYAMVADA.tokensAssetPath to tokensFile,
                ),
                espeakDir = espeakDir,
            )
            assertEquals(null, ok)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
