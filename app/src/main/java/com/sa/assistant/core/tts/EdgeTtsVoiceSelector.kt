package com.sa.assistant.core.tts

/**
 * Picks which Edge neural voice should read a given reply, based on the
 * actual characters in the text — not a user setting, per the requirement
 * that language be auto-detected. Real, simple, and honest: Devanagari
 * script (Hindi) selects the Hindi voice, everything else (including
 * Hindi written in Latin/Roman script, which this can't tell apart from
 * English by script alone) uses the English-India voice.
 */
object EdgeTtsVoiceSelector {

    const val HINDI_VOICE = "hi-IN-SwaraNeural"
    const val ENGLISH_INDIA_VOICE = "en-IN-NeerjaNeural"

    private val devanagariRange = '\u0900'..'\u097F'

    fun pickVoiceFor(text: String): String {
        val devanagariCount = text.count { it in devanagariRange }
        // Even a small amount of Devanagari script is a strong signal the line
        // is (at least partly) Hindi — SA's replies are often Hindi+English
        // mixed, and the Hindi voice reads embedded English words fine too.
        return if (devanagariCount > 0) HINDI_VOICE else ENGLISH_INDIA_VOICE
    }
}
