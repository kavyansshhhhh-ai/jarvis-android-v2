package app.shilex.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInterruptionTest {

    @Test
    fun stopTtsAndFlushClearSpeakingAndSpeechAmplitude() {
        val speaker = Speaker()
        assertEquals(0f, speaker.speechAmplitude.value, 0.01f)
        assertFalse(speaker.isSpeaking.value)

        // Stop/flush resets state safely even without an active AudioTrack or TTS engine
        speaker.stopTts()
        assertEquals(0f, speaker.speechAmplitude.value, 0.01f)
        assertFalse(speaker.isSpeaking.value)

        speaker.flush()
        assertEquals(0f, speaker.speechAmplitude.value, 0.01f)
        assertFalse(speaker.isSpeaking.value)
    }

    @Test
    fun referenceZeroFillsSafelyWithoutActiveOrAfterRelease() {
        val speaker = Speaker()
        val buf = ShortArray(64) { 123 }

        speaker.reference(buf, 0)
        assertTrue(buf.all { it.toInt() == 0 })

        buf.fill(456)
        speaker.flush()
        speaker.release()
        speaker.reference(buf, 16)
        assertTrue(buf.all { it.toInt() == 0 })
    }

    @Test
    fun previewVoiceAndStopPreviewClearPreviewStateWhenUnavailable() {
        val speaker = Speaker()
        var completed = false

        speaker.previewVoice("bundled_hi_female_priyamvada") { completed = true }
        assertTrue(completed)
        assertNull(speaker.previewingVoiceId.value)
        assertFalse(speaker.isSpeaking.value)

        completed = false
        speaker.previewVoice("unknown_voice") { completed = true }
        assertTrue(completed)
        assertNull(speaker.previewingVoiceId.value)
        assertFalse(speaker.isSpeaking.value)

        speaker.stopPreview()
        assertNull(speaker.previewingVoiceId.value)
        assertFalse(speaker.isSpeaking.value)
        speaker.release()
    }

    @Test
    fun previewPlaybackStateTracksIdleAndCancellation() {
        val speaker = Speaker()
        assertEquals(VoicePreviewState.Idle, speaker.previewPlayback.value.state)
        assertNull(speaker.previewPlayback.value.voiceId)

        var completedFemale = false
        speaker.previewVoice("bundled_hi_female_priyamvada") { completedFemale = true }
        assertTrue(completedFemale)
        assertEquals(VoicePreviewState.Idle, speaker.previewPlayback.value.state)

        var completedMale = false
        speaker.previewVoice("bundled_hi_male_pratham") { completedMale = true }
        assertTrue(completedMale)
        assertEquals(VoicePreviewState.Idle, speaker.previewPlayback.value.state)

        speaker.stopPreview()
        assertEquals(VoicePreviewState.Idle, speaker.previewPlayback.value.state)
        assertNull(speaker.previewingVoiceId.value)
        assertFalse(speaker.isSpeaking.value)
        speaker.release()
    }

    @Test
    fun femaleAndMaleVoiceConfigurationsProduceDistinctGenders() {
        val femaleVoice = BundledHindiVoices.find("bundled_hi_female_priyamvada")
        assertNotNull(femaleVoice)
        assertEquals(VoiceGender.Female, femaleVoice?.gender)

        val maleVoice = BundledHindiVoices.find("bundled_hi_male_pratham")
        assertNotNull(maleVoice)
        assertEquals(VoiceGender.Male, maleVoice?.gender)

        // All 5 female voices have VoiceGender.Female
        val allFemale = listOf(
            "bundled_hi_female_priyamvada",
            "bundled_hi_female_kokoro_alpha",
            "bundled_hi_female_kokoro_beta",
            "bundled_hi_female_supertonic_f1",
            "bundled_hi_female_supertonic_f2",
        )
        allFemale.forEach { id ->
            val v = BundledHindiVoices.find(id)
            assertNotNull(v)
            assertEquals(id, VoiceGender.Female, v?.gender)
        }

        // All 5 male voices have VoiceGender.Male
        val allMale = listOf(
            "bundled_hi_male_pratham",
            "bundled_hi_male_rohan",
            "bundled_hi_male_kokoro_omega",
            "bundled_hi_male_kokoro_psi",
            "bundled_hi_male_supertonic_m1",
        )
        allMale.forEach { id ->
            val v = BundledHindiVoices.find(id)
            assertNotNull(v)
            assertEquals(id, VoiceGender.Male, v?.gender)
        }
    }

    @Test
    fun pcmLevelDetectionIsAccurate() {
        assertEquals(0, pcmLevel(ByteArray(0)))
        val highEnergy = ByteArray(4).also {
            it[0] = 0x00
            it[1] = 0x40 // 16384
            it[2] = 0x00
            it[3] = 0x40
        }
        assertEquals(16384, pcmLevel(highEnergy))
    }
}
