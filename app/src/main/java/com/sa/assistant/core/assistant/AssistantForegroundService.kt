package com.sa.assistant.core.assistant

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sa.assistant.MainActivity
import com.sa.assistant.R
import com.sa.assistant.SaApplication
import com.sa.assistant.core.tts.SaTextToSpeech
import com.sa.assistant.core.wakeword.WakeWordListener
import com.sa.assistant.core.wakeword.WakeWordPreferences
import com.sa.assistant.data.model.TtsEngineState
import com.sa.assistant.data.model.WakeWordState
import com.sa.assistant.socket.SaSocketClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps [SaSocketClient] alive while the app is backgrounded (Phase 1), and
 * — as of Phase 6 Part 1 — actually drives [WakeWordListener]: it observes
 * [WakeWordPreferences] (enabled flag + configured phrase) to start/stop
 * listening, and on every [WakeWordListener.wakeDetected] event it brings
 * [MainActivity] to the front with [MainActivity.EXTRA_WAKE_TRIGGERED] so
 * the user lands straight on the Chat tab. The notification text also now
 * reflects the real [WakeWordListener.state] instead of a static string.
 */
@AndroidEntryPoint
class AssistantForegroundService : Service() {

    @Inject
    lateinit var socketClient: SaSocketClient

    @Inject
    lateinit var wakeWordListener: WakeWordListener

    @Inject
    lateinit var wakeWordPreferences: WakeWordPreferences

    @Inject
    lateinit var textToSpeech: SaTextToSpeech

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var preferencesJob: Job? = null
    private var wakeEventsJob: Job? = null
    private var stateJob: Job? = null
    private var ttsCoordinationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("SA is listening for you"))
        socketClient.start()

        preferencesJob = scope.launch {
            combine(wakeWordPreferences.isEnabled, wakeWordPreferences.wakePhrase) { enabled, phrase ->
                enabled to phrase
            }.collect { (enabled, phrase) ->
                if (enabled) {
                    wakeWordListener.start(phrase)
                } else {
                    wakeWordListener.stop()
                }
            }
        }

        wakeEventsJob = scope.launch {
            wakeWordListener.wakeDetected.collect {
                launchChatOnWake()
            }
        }

        stateJob = scope.launch {
            wakeWordListener.state.collect { state ->
                updateNotification(state)
            }
        }

        // Bug fix: without this, the wake-word mic stays open while SA is
        // speaking (TTS) and can hear/react to its own voice, or spam
        // restart because the recognizer picks up playback as noise.
        ttsCoordinationJob = scope.launch {
            textToSpeech.state.collect { ttsState ->
                if (ttsState == TtsEngineState.SPEAKING) {
                    wakeWordListener.pauseForSpeech()
                } else {
                    wakeWordListener.resumeAfterSpeech()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the OS kills this service under memory pressure,
        // restart it (without redelivering the last intent) so the socket
        // link and wake-word listening resume automatically.
        return START_STICKY
    }

    override fun onDestroy() {
        preferencesJob?.cancel()
        wakeEventsJob?.cancel()
        stateJob?.cancel()
        ttsCoordinationJob?.cancel()
        wakeWordListener.stop()
        socketClient.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun launchChatOnWake() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WAKE_TRIGGERED, true)
        }
        startActivity(intent)
    }

    private fun updateNotification(state: WakeWordState) {
        val text = when (state) {
            WakeWordState.LISTENING -> "SA is listening for you"
            WakeWordState.MIC_PERMISSION_REQUIRED -> "SA: mic permission needed for wake word"
            WakeWordState.RECOGNIZER_UNAVAILABLE -> "SA: no speech recognizer on this device"
            WakeWordState.ERROR -> "SA is reconnecting its wake-word listener..."
            WakeWordState.IDLE -> "SA is running in the background"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(this, SaApplication.ASSISTANT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            NotificationCompat.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
