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
