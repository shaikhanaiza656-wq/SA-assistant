package com.sa.assistant.core.wakeword

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real, continuous "Hey SA"-style wake-word listener. This is NOT a keyword-
 * spotting model (Porcupine/ONNX) trained offline for the single word "SA" —
 * building one requires a licensed console + a trained model file, which
 * can't be produced honestly in this environment. Instead this drives
 * Android's own [SpeechRecognizer] in a loop: each session transcribes
 * whatever is said, [android.speech.RecognitionListener.onResults]/[onError]
 * fires, and — unless [stop] was called — a new session starts right after.
 * [com.sa.assistant.data.model.matchesWakePhrase] checks every transcript
 * against [DEFAULT_WAKE_PHRASE] ("SA").
 *
 * Honest limitation: [SpeechRecognizer] needs a speech-recognition service
 * to be present on the device (normally the Google app) and, on most
 * devices, an internet connection unless the user has downloaded an offline
 * language pack in Android's own Settings > System > Languages > On-device
 * recognition. If neither is available, [state] goes to
 * [WakeWordState.RECOGNIZER_UNAVAILABLE] and this class does not pretend to
 * be listening.
 *
 * Must be started/stopped from a thread with a [Looper] — [start]/[stop]
 * post through [mainHandler] so callers (the foreground service) never need
 * to worry about this.
 */
@Singleton
class WakeWordListener @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var currentWakePhrase = DEFAULT_WAKE_PHRASE

    // --- Fix 1: don't listen to our own TTS voice ---
    // While SA is speaking (TTS), the recognizer must be fully torn down —
    // otherwise it hears SA's own playback through the mic and can
    // false-trigger or spam restarts. AssistantForegroundService calls
    // pauseForSpeech()/resumeAfterSpeech() around TTS start/stop.
    private var isPausedForSpeech = false

    // --- Fix 2: silence the recognizer's start/stop beep ---
    // Every SpeechRecognizer session plays a system "ding" on start and
    // stop. With a session restarting every ~400ms this became a constant
    // beeping loop. We briefly mute STREAM_MUSIC (where the beep plays)
    // around each start/stop transition. Ref-counted so overlapping
    // mute/unmute calls (start right after a stop) can't cancel each other out.
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
    /** Emits once each time [currentWakePhrase] is heard. Conflated: a caller that's slow to
     *  react won't build up a backlog of stale triggers. */
    val wakeDetected: Flow<Unit> = wakeEvents.receiveAsFlow()

    private val commandEvents = Channel<String>(Channel.BUFFERED)
    /**
     * Emits the actual spoken command text once it's been captured — either
     * extracted from the same breath as the wake word ("SA volume badha
     * do") or from the dedicated one-shot follow-up session that opens when
     * the user says just "SA" and pauses. The caller (AssistantForegroundService)
     * sends this text through the normal chat pipeline, same as if it had
     * been typed.
     */
    val commandCaptured: Flow<String> = commandEvents.receiveAsFlow()

    /** Starts (or restarts, if the phrase changed) continuous listening for [wakePhrase]. Safe to call repeatedly. */
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
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _state.value = WakeWordState.RECOGNIZER_UNAVAILABLE
                return@post
            }

            isRunning = true
            createRecognizerAndListen()
        }
    }

    /** Stops listening and releases the recognizer. Safe to call even if not running. */
    fun stop() {
        mainHandler.post {
            isRunning = false
            isPausedForSpeech = false
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            muteBeep()
            recognizer?.let {
                it.stopListening()
                it.destroy()
            }
            recognizer = null
            _state.value = WakeWordState.IDLE
            mainHandler.postDelayed({ unmuteBeep() }, BEEP_MUTE_MS)
        }
    }

    /**
     * Tears the recognizer down without touching [isRunning] or the user's
     * enabled-preference — call right before [SaTextToSpeech] starts
     * speaking so the mic can't hear SA's own voice. Pairs with
     * [resumeAfterSpeech]. Safe to call repeatedly / when not running.
     */
    fun pauseForSpeech() {
        mainHandler.post {
            if (!isRunning || isPausedForSpeech) return@post
            isPausedForSpeech = true
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            muteBeep()
            recognizer?.let {
                it.stopListening()
                it.destroy()
            }
            recognizer = null
            _state.value = WakeWordState.IDLE
            mainHandler.postDelayed({ unmuteBeep() }, BEEP_MUTE_MS)
        }
    }

    /** Resumes listening after [pauseForSpeech] — call once TTS playback finishes. */
    fun resumeAfterSpeech() {
        mainHandler.post {
            if (!isRunning || !isPausedForSpeech) return@post
            isPausedForSpeech = false
            createRecognizerAndListen()
        }
    }

    private fun createRecognizerAndListen() {
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
                    scheduleRestart()
                    return
                }
                Log.d(TAG, "Wake phrase matched")
                wakeEvents.trySend(Unit)
                val remainder = extractCommandAfterWake(matchedTranscript, currentWakePhrase)
                if (remainder != null) {
                    // "SA volume badha do" said in one breath — no follow-up needed.
                    commandEvents.trySend(remainder)
                    scheduleRestart()
                } else {
                    // Just "SA" alone — open a dedicated one-shot session for the command.
                    startCommandCaptureSession()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // A wake word doesn't need to wait for a full silence-terminated
                // result — checking partials makes detection feel instant. We
                // still let onResults do the actual command-extraction/capture
                // decision once this session finalizes below.
                val transcripts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (transcripts.any { matchesWakePhrase(it, currentWakePhrase) }) {
                    r.stopListening()
                }
            }

            override fun onError(error: Int) {
                Log.d(TAG, "Recognizer error code=$error — will restart listening")
                _state.value = WakeWordState.ERROR
                scheduleRestart()
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

    /** One-shot session dedicated to capturing the command after a bare "SA" (no follow-up words yet). */
    private fun startCommandCaptureSession() {
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
                scheduleRestart()
            }

            override fun onError(error: Int) {
                Log.d(TAG, "Command-capture error code=$error — nothing captured, resuming wake listening")
                scheduleRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) { /* wait for final — command needs the whole sentence */ }
            override fun onEndOfSpeech() { /* handled via onResults/onError */ }
            override fun onBeginningOfSpeech() { /* no-op */ }
            override fun onRmsChanged(rmsdB: Float) { /* no-op */ }
            override fun onBufferReceived(buffer: ByteArray?) { /* no-op */ }
            override fun onEvent(eventType: Int, params: Bundle?) { /* no-op */ }
        })
        r.startListening(commandCaptureIntent())
        mainHandler.postDelayed({ unmuteBeep() }, BEEP_MUTE_MS)
    }

    private fun scheduleRestart() {
        muteBeep()
        recognizer?.destroy()
        recognizer = null
        mainHandler.postDelayed({ unmuteBeep() }, BEEP_MUTE_MS)
        if (!isRunning || isPausedForSpeech) return
        // A short delay avoids hammering the recognizer service in a tight
        // loop when it errors out repeatedly (e.g. no network).
        mainHandler.postAtTime(
            { if (isRunning && !isPausedForSpeech) createRecognizerAndListen() },
            RESTART_TOKEN,
            android.os.SystemClock.uptimeMillis() + RESTART_DELAY_MS
        )
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    /** Same as [recognizerIntent] but with more silence tolerance — a follow-up command
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
        private val RESTART_TOKEN = Any()
    }
}
