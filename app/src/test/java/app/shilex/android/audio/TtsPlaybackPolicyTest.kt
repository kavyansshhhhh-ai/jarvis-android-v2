package app.shilex.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackPolicyTest {
    @Test
    fun focusLossFlushesSpeechButGainDoesNot() {
        assertTrue(TtsPlaybackPolicy.shouldFlushOnAudioFocusChange(-1))
        assertTrue(TtsPlaybackPolicy.shouldFlushOnAudioFocusChange(-2))
        assertTrue(TtsPlaybackPolicy.shouldFlushOnAudioFocusChange(-3))
        assertFalse(TtsPlaybackPolicy.shouldFlushOnAudioFocusChange(0))
        assertFalse(TtsPlaybackPolicy.shouldFlushOnAudioFocusChange(1))
    }

    @Test
    fun acceptsOnlyTheActiveUtteranceAndGeneration() {
        assertTrue(TtsPlaybackPolicy.isCurrentCallback("reply-1", "reply-1", 4L, 4L))
        assertFalse(TtsPlaybackPolicy.isCurrentCallback("reply-1", "reply-2", 4L, 4L))
        assertFalse(TtsPlaybackPolicy.isCurrentCallback("reply-1", "reply-1", 3L, 4L))
        assertFalse(TtsPlaybackPolicy.isCurrentCallback(null, "reply-1", 4L, 4L))
        assertFalse(TtsPlaybackPolicy.isCurrentCallback("reply-1", "reply-1", null, 4L))
    }

    @Test
    fun detectsPendingOfflineAndActivePreviewAsBusySpeech() {
        assertFalse(
            TtsPlaybackPolicy.hasPendingOrActiveSpeech(
                isPlaying = false,
                hasPendingUtterance = false,
                hasActivePreview = false,
                replyOpen = false,
                audible = false,
            ),
        )
        assertTrue(
            TtsPlaybackPolicy.hasPendingOrActiveSpeech(
                isPlaying = false,
                hasPendingUtterance = true,
                hasActivePreview = false,
                replyOpen = false,
                audible = false,
            ),
        )
        assertTrue(
            TtsPlaybackPolicy.hasPendingOrActiveSpeech(
                isPlaying = false,
                hasPendingUtterance = false,
                hasActivePreview = true,
                replyOpen = false,
                audible = false,
            ),
        )
        assertTrue(
            TtsPlaybackPolicy.hasPendingOrActiveSpeech(
                isPlaying = false,
                hasPendingUtterance = false,
                hasActivePreview = false,
                replyOpen = true,
                audible = false,
            ),
        )
    }

    @Test
    fun detectsMutedOrZeroMediaVolume() {
        assertTrue(TtsPlaybackPolicy.isMediaMutedOrZeroVolume(currentVolume = 0, isStreamMuted = false))
        assertTrue(TtsPlaybackPolicy.isMediaMutedOrZeroVolume(currentVolume = 5, isStreamMuted = true))
        assertFalse(TtsPlaybackPolicy.isMediaMutedOrZeroVolume(currentVolume = 5, isStreamMuted = false))
    }
}
