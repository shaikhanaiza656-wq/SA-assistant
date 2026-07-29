package com.sa.assistant.core.assistant

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.sa.assistant.R
import com.sa.assistant.SaApplication
import com.sa.assistant.core.automation.AutomationCommandExecutor
import com.sa.assistant.core.tts.SaTextToSpeech
import com.sa.assistant.core.tts.TtsPreferences
import com.sa.assistant.core.wakeword.WakeWordListener
import com.sa.assistant.core.wakeword.WakeWordPreferences
import com.sa.assistant.data.model.AutomationCommand
import com.sa.assistant.data.model.TtsEngineState
import com.sa.assistant.data.model.WakeWordState
import com.sa.assistant.data.repository.ChatRepository
import com.sa.assistant.socket.SaSocketClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Keeps [SaSocketClient] alive while the app is backgrounded (Phase 1), and
 * drives [WakeWordListener]: observes [WakeWordPreferences] (enabled flag +
 * configured phrase) to start/stop listening, coordinates it with
 * [SaTextToSpeech] so the mic can't hear SA's own voice, and — the real
 * point of the whole background pipeline — takes whatever command
 * [WakeWordListener.commandCaptured] reports, sends it through the exact
 * same chat pipeline a typed message uses ([ChatRepository.sendMessage]),
 * runs any real automation action the reply carries
 * ([AutomationCommandExecutor]), and speaks the outcome back
 * ([SaTextToSpeech]) — all without needing [com.sa.assistant.MainActivity]
 * to be open. [VoiceReplyDedupe] stops that same response from being
 * double-executed/double-spoken if the Chat screen also happens to be open
 * at the time.
 *
 * Honest limitation: [response.action] only ever arrives if the Termux
 * Python server (a separate codebase, not part of this Android project)
 * actually decided on and sent an action for what was said. If the server
 * hasn't been updated to do that yet, the spoken command still reaches it
 * and gets a real spoken reply back — it just won't trigger on-device
 * automation until the server-side half sends `action`/`actionParams`.
 */
@AndroidEntryPoint
class AssistantForegroundService : Service() {

    @Inject
    lateinit var socketClient: SaSocketClient

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var wakeWordListener: WakeWordListener

    @Inject
    lateinit var wakeWordPreferences: WakeWordPreferences

    @Inject
    lateinit var textToSpeech: SaTextToSpeech

    @Inject
    lateinit var ttsPreferences: TtsPreferences

    @Inject
    lateinit var automationExecutor: AutomationCommandExecutor

    @Inject
    lateinit var voiceReplyDedupe: VoiceReplyDedupe

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var preferencesJob: Job? = null
    private var stateJob: Job? = null
    private var ttsCoordinationJob: Job? = null
    private var commandCapturedJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("SA is listening for you"))
        acquireWakeLock()
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

        // The real "wake -> command -> action -> spoken confirmation" loop.
        // Deliberately does NOT bring MainActivity to the foreground —
        // this is meant to run silently in the background; the only
        // feedback the user gets is the spoken confirmation (and the
        // notification text below), same as any other background voice
        // assistant.
        commandCapturedJob = scope.launch {
            wakeWordListener.commandCaptured.collect { commandText ->
                handleVoiceCommand(commandText)
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
        stateJob?.cancel()
        ttsCoordinationJob?.cancel()
        commandCapturedJob?.cancel()
        wakeWordListener.stop()
        socketClient.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Real [PowerManager.PARTIAL_WAKE_LOCK], held only while this foreground
     * service is alive — keeps the CPU (not the screen) running so Porcupine's
     * continuous audio pipeline and the socket connection keep working with
     * the screen off/locked. Auto-timeout is a real safety net (10h, renewed
     * every time the service restarts) so a crash can't pin it forever; the
     * matching [releaseWakeLock] call in [onDestroy] is the normal path.
     * Actual Doze/App-Standby exemption (so Android doesn't throttle this
     * service at all) is a separate, user-consented step — see
     * SettingsViewModel.requestBatteryOptimizationExemption().
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SA:wakeWordService").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleVoiceCommand(commandText: String) {
        if (commandText.isBlank()) return
        scope.launch {
            updateNotification(WakeWordState.CAPTURING_COMMAND, overrideText = "SA: \"$commandText\" — bhej raha hoon...")
            val id = chatRepository.sendMessage(commandText)
            val response = withTimeoutOrNull(SERVER_REPLY_TIMEOUT_MS) {
                chatRepository.responses.first { it.id == id && it.done }
            }
            if (response == null) {
                speakNow("Server se jawab nahi aaya, connection check karo.")
                return@launch
            }
            when {
                response.action != null && voiceReplyDedupe.claimAction(id) -> {
                    val outcome = automationExecutor.execute(
                        AutomationCommand.fromWire(response.action, response.actionParams)
                    )
                    speakNow(outcome.message)
                }
                response.action == null && voiceReplyDedupe.claimSpeak(id) -> {
                    speakNow(response.reply)
                }
                // else: the Chat screen's own collector already claimed this
                // response first (screen was open) — nothing left to do here.
            }
        }
    }

    private suspend fun speakNow(text: String) {
        val prefs = ttsPreferences.snapshot.first()
        textToSpeech.speak(text, rate = prefs.speechRate, pitch = prefs.pitch, voiceName = prefs.voiceName)
    }

    private fun updateNotification(state: WakeWordState, overrideText: String? = null) {
        val text = overrideText ?: when (state) {
            WakeWordState.SPOTTING -> "SA is listening for \"SA\""
            WakeWordState.LISTENING -> "SA is listening for you"
            WakeWordState.CAPTURING_COMMAND -> "SA: sun raha hoon, bolo..."
            WakeWordState.MIC_PERMISSION_REQUIRED -> "SA: mic permission needed for wake word"
            WakeWordState.RECOGNIZER_UNAVAILABLE -> "SA: no speech recognizer on this device"
            WakeWordState.PORCUPINE_NOT_CONFIGURED -> "SA: add Picovoice AccessKey in Settings for always-on wake word"
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
        private const val SERVER_REPLY_TIMEOUT_MS = 15_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L // 10h safety cap, not "forever"
    }
}
