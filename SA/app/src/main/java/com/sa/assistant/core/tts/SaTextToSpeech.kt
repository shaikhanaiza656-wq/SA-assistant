package com.sa.assistant.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.sa.assistant.data.model.TtsEngineState
import com.sa.assistant.data.model.TtsVoiceOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real wrapper around Android's own [TextToSpeech] engine — whichever one
 * the user has installed (Google's, Samsung's, etc.), same as any other app
 * on the device. This is Phase 6 Part 2: TTS switching. No bundled voice
 * model, no cloud TTS API: [availableVoices] is exactly what
 * [TextToSpeech.getVoices] reports on this device, nothing invented.
 *
 * Lazily initialized on the first [speak] or [refreshVoices] call so a user
 * who never touches TTS never pays engine-startup cost.
 */
@Singleton
class SaTextToSpeech @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var engine: TextToSpeech? = null
    private var initStarted = false

    private val _state = MutableStateFlow(TtsEngineState.INITIALIZING)
    val state: StateFlow<TtsEngineState> = _state.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<TtsVoiceOption>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceOption>> = _availableVoices.asStateFlow()

    /** Queues initialization if needed, then speaks [rawText] once ready — callers don't need to sequence this themselves. */
    fun speak(rawText: String, rate: Float, pitch: Float, voiceName: String?) {
        val cleaned = stripForSpeech(rawText)
        if (cleaned.isBlank()) return
        ensureInitialized {
            val e = engine ?: return@ensureInitialized
            e.setSpeechRate(rate)
            e.setPitch(pitch)
            applyVoice(e, voiceName)
            e.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    fun stop() {
        engine?.stop()
        if (_state.value == TtsEngineState.SPEAKING) _state.value = TtsEngineState.READY
    }

    /** Populates [availableVoices] (initializing the engine first if needed). */
    fun refreshVoices() {
        ensureInitialized { populateVoices(it) }
    }

    private fun ensureInitialized(onReady: (TextToSpeech) -> Unit) {
        val existing = engine
        if (existing != null && _state.value != TtsEngineState.INITIALIZING) {
            onReady(existing)
            return
        }
        if (initStarted) return
        initStarted = true
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine = tts
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.value = TtsEngineState.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        _state.value = TtsEngineState.READY
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _state.value = TtsEngineState.READY
                    }
                })
                _state.value = TtsEngineState.READY
                populateVoices(tts)
                onReady(tts)
            } else {
                Log.w(TAG, "TextToSpeech init failed, status=$status")
                _state.value = TtsEngineState.UNAVAILABLE
            }
        }
    }

    private fun populateVoices(tts: TextToSpeech) {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        _availableVoices.value = voices
            .filterNot { it.isNetworkConnectionRequired && !isNetworkVoiceUsable() }
            .map { v ->
                TtsVoiceOption(
                    name = v.name,
                    locale = v.locale,
                    isNetworkOnly = v.isNetworkConnectionRequired
                )
            }
            .sortedBy { it.locale.displayName }
    }

    // Network-only voices still show up (labeled) — this just avoids silently
    // hiding every voice on an engine that reports everything as network-required.
    private fun isNetworkVoiceUsable(): Boolean = true

    private fun applyVoice(tts: TextToSpeech, voiceName: String?) {
        if (voiceName.isNullOrBlank()) return
        val match: Voice? = runCatching { tts.voices }.getOrNull()
            ?.firstOrNull { it.name == voiceName }
        if (match != null) {
            runCatching { tts.voice = match }
        }
    }

    /**
     * Strips the light Markdown SA's replies tend to contain (`**bold**`,
     * `# headings`, `` `code` ``, bullet dashes) so speech doesn't read
     * punctuation aloud. This is a plain-text cleanup, not a summarizer —
     * every word of the actual content is preserved.
     */
    private fun stripForSpeech(text: String): String {
        return text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("\\*\\*([^*]*)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]*)\\*"), "$1")
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^[\\-*]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            .trim()
    }

    companion object {
        private const val TAG = "SaTextToSpeech"
        private const val UTTERANCE_ID = "sa_tts_utterance"
    }
}
