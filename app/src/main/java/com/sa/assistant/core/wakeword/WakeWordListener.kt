package com.sa.assistant.core.wakeword

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var currentWakePhrase = DEFAULT_WAKE_PHRASE

    private val _state = MutableStateFlow(WakeWordState.IDLE)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    private val wakeEvents = Channel<Unit>(Channel.CONFLATED)
    /** Emits once each time [currentWakePhrase] is heard. Conflated: a caller that's slow to
     *  react won't build up a backlog of stale triggers. */
    val wakeDetected: Flow<Unit> = wakeEvents.receiveAsFlow()

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
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            recognizer?.let {
                it.stopListening()
                it.destroy()
            }
            recognizer = null
            _state.value = WakeWordState.IDLE
        }
    }

    private fun createRecognizerAndListen() {
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = WakeWordState.LISTENING
            }

            override fun onResults(results: Bundle?) {
                handleTranscripts(results)
                scheduleRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // A wake word doesn't need to wait for a full silence-terminated
                // result — checking partials makes detection feel instant.
                if (handleTranscripts(partialResults)) {
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
    }

    /** Returns true if a wake match fired (so the caller can stop this session early). */
    private fun handleTranscripts(bundle: Bundle?): Boolean {
        val transcripts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val matched = transcripts.any { matchesWakePhrase(it, currentWakePhrase) }
        if (matched) {
            Log.d(TAG, "Wake phrase matched")
            wakeEvents.trySend(Unit)
        }
        return matched
    }

    private fun scheduleRestart() {
        recognizer?.destroy()
        recognizer = null
        if (!isRunning) return
        // A short delay avoids hammering the recognizer service in a tight
        // loop when it errors out repeatedly (e.g. no network).
        mainHandler.postAtTime(
            { if (isRunning) createRecognizerAndListen() },
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

    companion object {
        private const val TAG = "WakeWordListener"
        private const val RESTART_DELAY_MS = 400L
        private val RESTART_TOKEN = Any()
    }
}
