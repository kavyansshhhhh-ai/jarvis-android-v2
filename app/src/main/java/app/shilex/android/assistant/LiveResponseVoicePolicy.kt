package app.shilex.android.assistant

/** Decisions for applying a user's local TTS selection to Gemini Live responses. */
internal object LiveResponseVoicePolicy {
    const val MAX_RETAINED_AUDIO_BYTES = 8 * 1024 * 1024

    fun shouldUseSelectedVoice(savedVoiceId: String?): Boolean = !savedVoiceId.isNullOrBlank()

    fun canRetainAudio(retainedBytes: Int, incomingBytes: Int): Boolean =
        retainedBytes >= 0 && incomingBytes > 0 &&
            retainedBytes <= MAX_RETAINED_AUDIO_BYTES - incomingBytes
}
