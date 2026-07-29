package com.sa.assistant.data.model

import java.util.Locale

/**
 * Live state of [com.sa.assistant.core.tts.SaTextToSpeech], surfaced to
 * [com.sa.assistant.ui.settings.SettingsScreen] the same honest way
 * [WakeWordState] mirrors the wake-word listener — the UI never guesses
 * whether SA can actually talk right now.
 */
enum class TtsEngineState {
    /** [android.speech.tts.TextToSpeech] hasn't finished (or hasn't started) initializing. */
    INITIALIZING,

    /** Engine ready; not currently speaking. */
    READY,

    /** Currently reading a message (or the Settings test line) aloud. */
    SPEAKING,

    /** [android.speech.tts.TextToSpeech.OnInitListener] reported [android.speech.tts.TextToSpeech.ERROR] —
     *  no TTS engine is installed/usable on this device. */
    UNAVAILABLE
}

/**
 * A voice the device's TTS engine actually offers, per
 * [android.speech.tts.TextToSpeech.getVoices]. [name] is the engine's own
 * voice identifier (what gets persisted and passed back into
 * [android.speech.tts.Voice] lookup) — not a made-up label.
 */
data class TtsVoiceOption(
    val name: String,
    val locale: Locale,
    val isNetworkOnly: Boolean
) {
    /** Human-readable label for the picker, e.g. "English (India)" or "Hindi (India) — network". */
    fun displayLabel(): String {
        val localeLabel = locale.displayName.ifBlank { locale.toString() }
        return if (isNetworkOnly) "$localeLabel — network" else localeLabel
    }
}

/** Persisted TTS settings. Rate/pitch use Android's own 0.5x–2.0x scale (1.0 = normal). */
data class TtsPrefsSnapshot(
    val isEnabled: Boolean,
    val voiceName: String?,
    val speechRate: Float,
    val pitch: Float
)

const val TTS_RATE_MIN = 0.5f
const val TTS_RATE_MAX = 2.0f
const val TTS_PITCH_MIN = 0.5f
const val TTS_PITCH_MAX = 2.0f
const val TTS_DEFAULT_RATE = 1.0f
const val TTS_DEFAULT_PITCH = 1.0f
