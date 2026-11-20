package app.shilex.android.audio

/** Chooses Supertonic's language hint from the text script without changing the selected voice. */
object TtsLanguagePolicy {
    fun supertonicLanguage(text: String): String {
        var devanagariLetters = 0
        var otherLetters = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (Character.isLetter(codePoint)) {
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.DEVANAGARI) {
                    devanagariLetters++
                } else {
                    otherLetters++
                }
            }
            offset += Character.charCount(codePoint)
        }
        return if (devanagariLetters > 0 && devanagariLetters >= otherLetters) "hi" else "en"
    }
}
