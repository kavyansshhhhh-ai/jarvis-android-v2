package app.shilex.android.audio

/** Pure callback gate so late TTS callbacks cannot change the current utterance's UI state. */
internal object TtsPlaybackPolicy {
    /** Android reports all focus losses with negative values; gains and no-change are nonnegative. */
    fun shouldFlushOnAudioFocusChange(focusChange: Int): Boolean = focusChange < 0

    fun isCurrentCallback(
        callbackUtteranceId: String?,
        activeUtteranceId: String?,
        callbackGeneration: Long?,
        activeGeneration: Long,
    ): Boolean = callbackUtteranceId != null &&
        callbackUtteranceId == activeUtteranceId &&
        callbackGeneration != null &&
        callbackGeneration == activeGeneration

    fun hasPendingOrActiveSpeech(
        isPlaying: Boolean,
        hasPendingUtterance: Boolean,
        hasActivePreview: Boolean,
        replyOpen: Boolean,
        audible: Boolean,
    ): Boolean = isPlaying || hasPendingUtterance || hasActivePreview || replyOpen || audible

    fun isMediaMutedOrZeroVolume(currentVolume: Int, isStreamMuted: Boolean): Boolean =
        isStreamMuted || currentVolume <= 0
}
