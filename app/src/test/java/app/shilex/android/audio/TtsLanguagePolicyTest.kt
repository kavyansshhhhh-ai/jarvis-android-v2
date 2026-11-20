package app.shilex.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsLanguagePolicyTest {
    @Test
    fun selectsHindiForDevanagariAndEnglishForLatinText() {
        assertEquals("hi", TtsLanguagePolicy.supertonicLanguage("नमस्ते! मैं आपकी कैसे मदद कर सकता हूँ?"))
        assertEquals("en", TtsLanguagePolicy.supertonicLanguage("Hello, how can I help you?"))
    }

    @Test
    fun mixedTextUsesDominantScriptAndEmptyTextDefaultsToEnglish() {
        assertEquals("hi", TtsLanguagePolicy.supertonicLanguage("नमस्ते ji"))
        assertEquals("en", TtsLanguagePolicy.supertonicLanguage("JARVIS says hello नमस्ते"))
        assertEquals("en", TtsLanguagePolicy.supertonicLanguage(""))
    }
}
