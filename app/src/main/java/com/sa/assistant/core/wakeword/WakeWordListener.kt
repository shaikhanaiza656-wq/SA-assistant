package com.sa.assistant.core.wakeword

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.sa.assistant.data.model.DEFAULT_WAKE_PHRASE
import com.sa.assistant.data.model.WakeWordState
import com.sa.assistant.data.model.extractCommandAfterWake
import com.sa.assistant.data.model.matchesWakePhrase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wake-word listener for "SA". Two real spotting paths, chosen automatically:
 *
 * 1. **Porcupine** ([PorcupineWakeWordEngine]) — the real always-on path.
 *    Runs continuously with no SpeechRecognizer restart loop and no network
 *    dependency, exactly like any commercial always-on assistant. Requires
 *    the user's own Picovoice AccessKey + a custom "SA" keyword file trained
 *    in Picovoice Console (see [PorcupineWakeWordEngine] kdoc) — this is a
 *    one-time, free, ~2-minute setup this code can't do on someone's behalf,
 *    because a "SA" keyword model can only honestly come from Picovoice's
 *    own trainer.
 * 2. **Legacy SpeechRecognizer loop** — used automatically only until #1 is
 *    configured, so wake-word detection never silently stops working. Once
 *    Porcupine credentials are added in Settings this path is not used.
 *
 * Either way, once the wake word fires, exactly one dedicated
 * [SpeechRecognizer] session opens once to capture the actual command (or
 * the remainder is reused if it was said in the same breath as "SA") — this
 * one-shot session is never in a restart loop. [MicArbiter] guarantees
 * Porcupine and that session are never both holding the mic.
 */
@Singleton
class WakeWordListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val porcupineEngine: PorcupineWakeWordEngine,
    private val micArbiter: MicArbiter,
    private val wakeWordPreferences: WakeWordPreferences
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var currentWakePhrase = DEFAULT_WAKE_PHRASE
    private var isPausedForSpeech = false
    private var usingPorcupine = false

    // Cached from WakeWordPreferences so start()/restart logic never has to
    // block the main thread on a suspend read.
    @Volatile private var cachedAccessKey: String = ""
    @Volatile private var cachedKeywordAsset: String = WakeWordPreferences.DEFAULT_KEYWORD_ASSET

    init {
        prefsScope.launch {
            combine(wakeWordPreferences.porcupineAccessKey, wakeWordPreferences.porcupineKeywordAsset) { key, asset ->
                key.orEmpty() to asset
            }.collect { (key, asset) ->
                val credentialsChanged = key != cachedAccessKey || asset != cachedKeywordAsset
                cachedAccessKey = key
                cachedKeywordAsset = asset
                // If the user just pasted valid credentials while already running
                // on the legacy fallback, hot-swap to Porcupine without needing a
                // manual toggle-off/on.
                if (credentialsChanged && isRunning && !usingPorcupine && !isPausedForSpeech) {
                    stopLegacyRecognizer()
                    startSpottingPreferPorcupine()
                }
            }
        }
    }

    // --- Fix: silence the recognizer's start/stop beep on the legacy path/
    // command-capture session (Porcupine has no such beep at all). ---
    private var muteDepth = 0
    private fun muteBeep() {
        if (muteDepth == 0) {
            runCatching { audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
        }
        muteDepth++
    }
    private fun unmuteBeep() {
        if (muteDepth <= 0) return
        muteDepth--
        if (muteDepth == 0) {
            runCatching { audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
        }
    }

    private val _state = MutableStateFlow(WakeWordState.IDLE)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    private val wakeEvents = Channel<Unit>(Channel.CONFLATED)
    /** Emits once each time the wake word is heard. Conflated: a slow-reacting caller
     *  won't build up a backlog of stale triggers. */
    val wakeDetected: Flow<Unit> = wakeEvents.receiveAsFlow()

    private val commandEvents = Channel<String>(Channel.BUFFERED)
    /** Emits the spoken command text once captured. The caller (AssistantForegroundService)
     *  sends this through the normal chat pipeline, same as a typed message. */
    val commandCaptured: Flow<String> = commandEvents.receiveAsFlow()

    /** Starts (or restarts, if the phrase changed) wake-word listening. Safe to call repeatedly. */
    fun start(wakePhrase: String = DEFAULT_WAKE_PHRASE) {
        mainHandler.post {
            currentWakePhrase = wakePhrase
            if (isRunning) return@post

            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                _state.value = WakeWordState.MIC_PERMISSION_REQUIRED
                return@post
            }

            isRunning = true
            startSpottingPreferPorcupine()
        }
    }

    /** Stops listening entirely and releases every mic resource. Safe to call even if not running. */
    fun stop() {
        mainHandler.post {
            isRunning = false
            isPausedForSpeech = false
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            porcupineEngine.stop()
            stopLegacyRecognizer()
            usingPorcupine = false
            _state.value = WakeWordState.IDLE
        }
    }

    /**
     * Tears the active mic path down without touching [isRunning] — call
     * right before [com.sa.assistant.core.tts.SaTextToSpeech] starts
     * speaking so the mic can't hear SA's own voice. Pairs with
     * [resumeAfterSpeech]. Safe to call repeatedly / when not running.
     */
    fun pauseForSpeech() {
        mainHandler.post {
            if (!isRunning || isPausedForSpeech) return@post
            isPausedForSpeech = true
            if (usingPorcupine) {
                porcupineEngine.pauseForOtherMicUse()
            } else {
                mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
                stopLegacyRecognizer()
            }
            _state.value = WakeWordState.IDLE
        }
    }

    /** Resumes listening after [pauseForSpeech] — call once TTS playback finishes. */
    fun resumeAfterSpeech() {
        mainHandler.post {
            if (!isRunning || !isPausedForSpeech) return@post
            isPausedForSpeech = false
            if (usingPorcupine) {
                porcupineEngine.resume()
                _state.value = WakeWordState.SPOTTING
            } else {
                createRecognizerAndListen()
            }
        }
    }

    // --- Spotting path selection -------------------------------------------------

    private fun startSpottingPreferPorcupine() {
        try {
            porcupineEngine.start(cachedAccessKey, cachedKeywordAsset) {
                // Fires on Porcupine's own callback thread — hop back to main
                // before touching anything else (mic, UI state, recognizer).
                mainHandler.post { onWakeSpotted() }
            }
            usingPorcupine = true
            _state.value = WakeWordState.SPOTTING
        } catch (e: PorcupineWakeWordEngine.PorcupineUnavailable) {
            Log.i(TAG, "Porcupine not usable yet (${e.message}) - using SpeechRecognizer-loop fallback")
            usingPorcupine = false
            _state.value = WakeWordState.PORCUPINE_NOT_CONFIGURED
            if (micArbiter.acquire(MicArbiter.Owner.SPEECH_RECOGNIZER)) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    _state.value = WakeWordState.RECOGNIZER_UNAVAILABLE
                    micArbiter.release(MicArbiter.Owner.SPEECH_RECOGNIZER)
                    return
                }
                createRecognizerAndListen()
            }
        }
    }

    private fun onWakeSpotted() {
        if (!isRunning) return
        Log.d(TAG, "Wake word spotted (Porcupine)")
        playActivationSound()
        wakeEvents.trySend(Unit)
        porcupineEngine.pauseForOtherMicUse()
        startCommandCaptureSession()
    }

    private fun playActivationSound() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, ACTIVATION_TONE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, ACTIVATION_TONE_MS)
            mainHandler.postDelayed({ runCatching { tone.release() } }, (ACTIVATION_TONE_MS + 50).toLong())
        }
    }

    // --- Legacy SpeechRecognizer-loop fallback (only used pre-Porcupine-setup) ---

    private fun createRecognizerAndListen() {
        if (!micArbiter.acquire(MicArbiter.Owner.SPEECH_RECOGNIZER)) return
        muteBeep()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = WakeWordState.LISTENING
            }

            override fun onResults(results: Bundle?) {
                val transcripts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val matchedTranscript = transcripts.firstOrNull { matchesWakePhrase(it, currentWakePhrase) }
                if (matchedTranscript == null) {
                    scheduleLegacyRestart()
                    return
                }
                Log.d(TAG, "Wake phrase matched (legacy fallback)")
                wakeEvents.trySend(Unit)
                playActivationSound()
                val remainder = extractCommandAfterWake(matchedTranscript, currentWakePhrase)
                stopLegacyRecognizer()
                if (remainder != null) {
                    commandEvents.trySend(remainder)
                    scheduleLegacyRestart()
                } else {
                    startCommandCaptureSession()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val transcripts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (transcripts.any { matchesWakePhrase(it, currentWakePhrase) }) {
                    r.stopListening()
                }
            }

            override fun onError(error: Int) {
                Log.d(TAG, "Recognizer error code=$error - will restart listening")
                _state.value = WakeWordState.ERROR
                scheduleLegacyRestart()
            }

            override fun onEndOfSpeech() { /* handled via onResults/onError */ }
            override fun onBeginningOfSpeech() { /* no-op */ }
            override fun onRmsChanged(rmsdB: Float) { /* no-op */ }
            override fun onBufferReceived(buffer: ByteArray?) { /* no-op */ }
            override fun onEvent(eventType: Int, params: Bundle?) { /* no-op */ }
        })
        r.startListening(recognizerIntent())
        mainHandler.postDelayed({ unmuteBeep() }, BEEP_MUTE_MS)
    }

    private fun stopLegacyRecognizer() {
        muteBeep()
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
        micArbiter.release(MicArbiter.Owner.SPEECH_RECOGNIZER)
        mainHandler.postDelayed({ unmuteBeep() }, BEEP_MUTE_MS)
    }

    private fun scheduleLegacyRestart() {
        stopLegacyRecognizer()
        if (!isRunning || isPausedForSpeech || usingPorcupine) return
        // Short delay avoids hammering the recognizer service in a tight loop
        // when it errors out repeatedly (e.g. no network) - this is the
        // fallback path only; Porcupine (once configured) never restarts a loop.
        mainHandler.postAtTime(
            { if (isRunning && !isPausedForSpeech && !usingPorcupine) createRecognizerAndListen() },
            RESTART_TOKEN,
            android.os.SystemClock.uptimeMillis() + RESTART_DELAY_MS
        )
    }

    // --- One-shot command capture, shared by both spotting paths -----------------

    /** Opens the mic exactly once to capture the command that follows the wake word. */
    private fun startCommandCaptureSession() {
        if (!micArbiter.acquire(MicArbiter.Owner.SPEECH_RECOGNIZER)) {
            // Mic somehow still held (shouldn't happen - Porcupine/legacy both
            // release before calling this) - resume spotting rather than getting stuck.
            resumeSpottingAfterCommandCapture()
            return
        }
        muteBeep()
        _state.value = WakeWordState.CAPTURING_COMMAND
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { /* stays CAPTURING_COMMAND */ }

            override fun onResults(results: Bundle?) {
                val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty().firstOrNull()?.trim()
                if (!transcript.isNullOrEmpty()) {
                    commandEvents.trySend(transcript)
                }
                finishCommandCapture()
            }

            override fun onError(error: Int) {
                Log.d(TAG, "Command-capture error code=$error - nothing captured, resuming wake spotting")
                finishCommandCapture()
            }

            override fun onPartialResults(partialResults: Bundle?) { /* wait for final - command needs the whole sentence */ }
            override fun onEndOfSpeech() { /* handled via onResults/onError */ }
            override fun onBeginningOfSpeech() { /* no-op */ }
            override fun onRmsChanged(rmsdB: Float) { /* no-op */ }
            override fun onBufferReceived(buffer: ByteArray?) { /* no-op */ }
            override fun onEvent(eventType: Int, params: Bundle?) { /* no-op */ }
        })
        r.startListening(commandCaptureIntent())
        mainHandler.postDelayed({ unmuteBeep() }, BEEP_MUTE_MS)
    }

    private fun finishCommandCapture() {
        muteBeep()
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
        micArbiter.release(MicArbiter.Owner.SPEECH_RECOGNIZER)
        mainHandler.postDelayed({ unmuteBeep() }, BEEP_MUTE_MS)
        resumeSpottingAfterCommandCapture()
    }

    private fun resumeSpottingAfterCommandCapture() {
        if (!isRunning || isPausedForSpeech) return
        if (usingPorcupine) {
            porcupineEngine.resume()
            _state.value = WakeWordState.SPOTTING
        } else {
            scheduleLegacyRestart()
        }
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    /** Same as [recognizerIntent] but with more silence tolerance - a follow-up command
     *  sentence is longer than a two-letter wake word and shouldn't cut off mid-sentence. */
    private fun commandCaptureIntent(): Intent =
        recognizerIntent().apply {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        }

    companion object {
        private const val TAG = "WakeWordListener"
        private const val RESTART_DELAY_MS = 400L
        private const val BEEP_MUTE_MS = 250L
        private const val ACTIVATION_TONE_MS = 120
        private const val ACTIVATION_TONE_VOLUME = 60
        private val RESTART_TOKEN = Any()
    }
}
