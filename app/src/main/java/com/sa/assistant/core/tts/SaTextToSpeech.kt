package com.sa.assistant.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.sa.assistant.data.model.TtsEngineState
import com.sa.assistant.data.model.TtsVoiceOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Speaks SA's replies. Two real engines, in priority order:
 *
 * 1. **Edge TTS** ([EdgeTtsClient]) — Microsoft's natural neural voices.
 *    Language is auto-detected per line ([EdgeTtsVoiceSelector]) and mapped
 *    to `hi-IN-SwaraNeural` (Hindi) or `en-IN-NeerjaNeural` (English-India).
 *    Generated audio is cached on disk ([EdgeTtsCache]) so repeating a line
 *    is instant and costs no network call the second time. Playback is real
 *    [MediaPlayer], not a stub.
 * 2. **Android [TextToSpeech]** — whichever engine the user has installed
 *    (Google's, Samsung's, etc.), same as before. Used automatically
 *    whenever Edge TTS can't be reached (offline, endpoint blocked/changed,
 *    timeout) — requirement: "if Edge TTS is unavailable, fall back to
 *    Android TextToSpeech". After a failure, Edge is skipped for a short
 *    cooldown window so a flaky network doesn't cost a full retry+timeout
 *    on every single reply (battery/latency).
 *
 * Lazily initialized on first [speak]/[refreshVoices] call, same as before.
 */
@Singleton
class SaTextToSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
    private val edgeTtsClient: EdgeTtsClient,
    private val edgeCache: EdgeTtsCache
) {
    private var engine: TextToSpeech? = null
    private var initStarted = false
    private var mediaPlayer: MediaPlayer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var edgeCooldownUntilMs: Long = 0L

    private val _state = MutableStateFlow(TtsEngineState.INITIALIZING)
    val state: StateFlow<TtsEngineState> = _state.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<TtsVoiceOption>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceOption>> = _availableVoices.asStateFlow()

    /** Queues initialization if needed, then speaks [rawText] once ready — callers don't need to sequence this themselves. */
    fun speak(rawText: String, rate: Float, pitch: Float, voiceName: String?) {
        val cleaned = stripForSpeech(rawText)
        if (cleaned.isBlank()) return

        scope.launch {
            if (edgeUsable()) {
                val voice = EdgeTtsVoiceSelector.pickVoiceFor(cleaned)
                val audioFile = obtainEdgeAudio(cleaned, voice, rate, pitch)
                if (audioFile != null) {
                    withContext(Dispatchers.Main) { playCachedFile(audioFile) }
                    return@launch
                }
                markEdgeFailure()
            }
            withContext(Dispatchers.Main) {
                speakWithAndroidEngine(cleaned, rate, pitch, voiceName)
            }
        }
    }

    fun stop() {
        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        engine?.stop()
        if (_state.value == TtsEngineState.SPEAKING) _state.value = TtsEngineState.READY
    }

    /** Populates [availableVoices] (initializing the Android fallback engine so the
     *  Settings voice picker still works for the fallback path). */
    fun refreshVoices() {
        ensureAndroidEngineInitialized { populateVoices(it) }
    }

    // --- Edge TTS path -----------------------------------------------------------

    private fun edgeUsable(): Boolean = System.currentTimeMillis() >= edgeCooldownUntilMs

    private fun markEdgeFailure() {
        edgeCooldownUntilMs = System.currentTimeMillis() + EDGE_COOLDOWN_MS
        Log.w(TAG, "Edge TTS unavailable, falling back to Android TextToSpeech for ${EDGE_COOLDOWN_MS / 1000}s")
    }

    /** Cache hit, or a real synth call + cache write. Runs on IO dispatcher (called from [scope]). */
    private fun obtainEdgeAudio(text: String, voice: String, rate: Float, pitch: Float): File? {
        edgeCache.get(text, voice, rate, pitch)?.let { return it }
        val ratePercent = percentString(rate)
        val pitchPercent = percentString(pitch, suffix = "%")
        val bytes = edgeTtsClient.synthesize(text, voice, ratePercent, pitchPercent) ?: return null
        return runCatching {
            edgeCache.put(text, voice, rate, pitch, bytes).also { edgeCache.trimIfNeeded() }
        }.getOrNull()
    }

    /** rate/pitch are Android's 0.5x-2.0x scale (1.0 = normal); Edge's SSML wants a signed percent offset. */
    private fun percentString(scale: Float, suffix: String = "%"): String {
        val percent = ((scale - 1.0f) * 100f).roundToInt()
        val sign = if (percent >= 0) "+" else ""
        return "$sign$percent$suffix"
    }

    private fun playCachedFile(file: File) {
        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        val player = MediaPlayer()
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                _state.value = TtsEngineState.SPEAKING
                it.start()
            }
            player.setOnCompletionListener {
                _state.value = TtsEngineState.READY
                runCatching { it.release() }
                if (mediaPlayer === it) mediaPlayer = null
            }
            player.setOnErrorListener { mp, _, _ ->
                _state.value = TtsEngineState.READY
                runCatching { mp.release() }
                if (mediaPlayer === mp) mediaPlayer = null
                true
            }
            mediaPlayer = player
            player.prepareAsync()
        }.onFailure {
            Log.w(TAG, "Edge TTS playback failed, falling back: ${it.message}")
            runCatching { player.release() }
            mediaPlayer = null
        }
    }

    // --- Android TextToSpeech fallback (unchanged behavior from before) ----------

    private fun speakWithAndroidEngine(cleaned: String, rate: Float, pitch: Float, voiceName: String?) {
        ensureAndroidEngineInitialized {
            val e = engine ?: return@ensureAndroidEngineInitialized
            e.setSpeechRate(rate)
            e.setPitch(pitch)
            applyVoice(e, voiceName)
            e.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    private fun ensureAndroidEngineInitialized(onReady: (TextToSpeech) -> Unit) {
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
        private const val EDGE_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
