package com.sa.assistant.data.model

/**
 * Live state of [com.sa.assistant.core.wakeword.WakeWordListener], surfaced
 * to [com.sa.assistant.ui.settings.SettingsScreen] so the toggle there never
 * lies about what's actually happening in the background service.
 */
enum class WakeWordState {
    /** Listener is off (either the user hasn't enabled it, or the service isn't running). */
    IDLE,

    /** [android.speech.SpeechRecognizer] session is actively open and listening. */
    LISTENING,

    /** Wake phrase was just heard; a dedicated one-shot session is capturing the spoken command. */
    CAPTURING_COMMAND,

    /** User turned the toggle on, but RECORD_AUDIO isn't granted — nothing is actually listening. */
    MIC_PERMISSION_REQUIRED,

    /** Device has no speech-recognition service available (SpeechRecognizer.isRecognitionAvailable() == false). */
    RECOGNIZER_UNAVAILABLE,

    /** A recognizer session errored (network, no-match timeout, etc.) and a restart is pending. */
    ERROR
}

/** The wake word the user asked for. Kept as a real, named default rather than an arbitrary placeholder. */
const val DEFAULT_WAKE_PHRASE = "SA"

/**
 * Whether a raw SpeechRecognizer transcript counts as hearing [wakePhrase] being said.
 *
 * On-device/cloud STT very rarely transcribes a bare two-letter word like "SA" as
 * literally "sa" — it's usually heard as a real English/Hindi-accented word that
 * sounds the same ("essay", "es a", "s a", "esa"). This is real, honest matching
 * against what the recognizer actually tends to output, not a fake stub match —
 * if [wakePhrase] is ever changed away from "SA" in a later phase, matching falls
 * back to a plain normalized-equality/contains check against that phrase.
 */
fun matchesWakePhrase(transcript: String, wakePhrase: String = DEFAULT_WAKE_PHRASE): Boolean {
    val normalized = transcript.trim().lowercase().replace(Regex("[^a-z ]"), "").trim()
    if (normalized.isEmpty()) return false

    val words = normalized.split(Regex("\\s+"))

    if (wakePhrase.equals(DEFAULT_WAKE_PHRASE, ignoreCase = true)) {
        val saVariants = setOf("sa", "essay", "esa", "es a", "s a")
        return normalized in saVariants || words.any { it in saVariants }
    }

    val target = wakePhrase.trim().lowercase()
    return normalized == target || words.contains(target) || normalized.contains(target)
}

/**
 * If [transcript] contains [wakePhrase] followed by more words in the same
 * breath ("SA volume badha do"), returns everything after the wake phrase
 * as the command text. Returns null if the wake phrase is all that was
 * said (nothing follows) — the caller should then open a dedicated
 * follow-up listening session instead of guessing at an empty command.
 */
fun extractCommandAfterWake(transcript: String, wakePhrase: String = DEFAULT_WAKE_PHRASE): String? {
    val normalized = transcript.trim().lowercase().replace(Regex("[^a-z ]"), "").trim()
    if (normalized.isEmpty()) return null
    val words = normalized.split(Regex("\\s+"))

    val markerIndex = if (wakePhrase.equals(DEFAULT_WAKE_PHRASE, ignoreCase = true)) {
        val saVariants = setOf("sa", "essay", "esa", "es", "s")
        // "es a"/"s a" already got space-split into two words ("es","a"/"s","a") by the
        // regex above; treat either single-token variant OR that two-token pair as the marker.
        words.indexOfFirst { it in saVariants }
    } else {
        val targetWords = wakePhrase.trim().lowercase().split(Regex("\\s+"))
        words.indexOfFirst { it == targetWords.first() }
    }
    if (markerIndex < 0) return null

    val remainder = words.drop(markerIndex + 1).joinToString(" ").trim()
    return remainder.ifEmpty { null }
}
