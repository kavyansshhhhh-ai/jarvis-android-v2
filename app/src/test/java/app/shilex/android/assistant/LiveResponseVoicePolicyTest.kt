package app.shilex.android.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveResponseVoicePolicyTest {
    @Test
    fun localVoiceIsUsedOnlyAfterTheUserHasChosenOne() {
        assertFalse(LiveResponseVoicePolicy.shouldUseSelectedVoice(null))
        assertFalse(LiveResponseVoicePolicy.shouldUseSelectedVoice(" "))
        assertTrue(LiveResponseVoicePolicy.shouldUseSelectedVoice("bundled_hi_female_priyamvada"))
    }

    @Test
    fun retainedAudioFallbackNeverExceedsItsMemoryBudget() {
        assertTrue(LiveResponseVoicePolicy.canRetainAudio(0, 1024))
        assertFalse(LiveResponseVoicePolicy.canRetainAudio(0, LiveResponseVoicePolicy.MAX_RETAINED_AUDIO_BYTES + 1))
        assertFalse(LiveResponseVoicePolicy.canRetainAudio(LiveResponseVoicePolicy.MAX_RETAINED_AUDIO_BYTES, 1))
        assertFalse(LiveResponseVoicePolicy.canRetainAudio(0, 0))
    }
}
