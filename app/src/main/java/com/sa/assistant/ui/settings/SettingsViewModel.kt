package com.sa.assistant.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sa.assistant.core.assistant.AssistantForegroundService
import com.sa.assistant.core.tts.SaTextToSpeech
import com.sa.assistant.core.tts.TtsPreferences
import com.sa.assistant.core.wakeword.WakeWordListener
import com.sa.assistant.core.wakeword.WakeWordPreferences
import com.sa.assistant.data.model.TTS_DEFAULT_PITCH
import com.sa.assistant.data.model.TTS_DEFAULT_RATE
import com.sa.assistant.data.model.TtsEngineState
import com.sa.assistant.data.model.TtsVoiceOption
import com.sa.assistant.data.model.WakeWordState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class WakeWordCombined(
    val enabled: Boolean,
    val phrase: String,
    val listenerState: WakeWordState,
    val porcupineAccessKey: String?
)

data class SettingsUiState(
    val isWakeWordEnabled: Boolean = false,
    val wakePhrase: String = "SA",
    val wakeWordState: WakeWordState = WakeWordState.IDLE,
    val hasMicPermission: Boolean = false,
    val isTtsEnabled: Boolean = false,
    val ttsEngineState: TtsEngineState = TtsEngineState.INITIALIZING,
    val ttsVoices: List<TtsVoiceOption> = emptyList(),
    val ttsSelectedVoiceName: String? = null,
    val ttsRate: Float = TTS_DEFAULT_RATE,
    val ttsPitch: Float = TTS_DEFAULT_PITCH,
    val porcupineAccessKey: String? = null
)

/**
 * Backs [SettingsScreen]. Phase 6 Part 1 (Wake Word) and Part 2 (TTS
 * switching) both live here: the wake-word toggle is the single source of
 * truth for [WakeWordPreferences] with [AssistantForegroundService] actually
 * driving [WakeWordListener] in the background; the TTS section is the
 * single source of truth for [TtsPreferences] with [SaTextToSpeech] mirrored
 * live (engine state + the real device voice list) so neither section ever
 * shows the user something that isn't actually true right now.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val wakeWordPreferences: WakeWordPreferences,
    private val wakeWordListener: WakeWordListener,
    private val ttsPreferences: TtsPreferences,
    private val tts: SaTextToSpeech
) : AndroidViewModel(application) {

    private val events = Channel<SettingsUiEvent>(Channel.BUFFERED)
    val uiEvents: Flow<SettingsUiEvent> = events.receiveAsFlow()

    private val wakeWordFlow = combine(
        wakeWordPreferences.isEnabled,
        wakeWordPreferences.wakePhrase,
        wakeWordListener.state,
        wakeWordPreferences.porcupineAccessKey
    ) { enabled, phrase, listenerState, accessKey -> WakeWordCombined(enabled, phrase, listenerState, accessKey) }

    private val ttsFlow = combine(
        ttsPreferences.snapshot,
        tts.state,
        tts.availableVoices
    ) { snapshot, engineState, voices -> Triple(snapshot, engineState, voices) }

    val uiState: StateFlow<SettingsUiState> = combine(wakeWordFlow, ttsFlow) { wake, ttsCombined ->
        val (snapshot, engineState, voices) = ttsCombined
        SettingsUiState(
            isWakeWordEnabled = wake.enabled,
            wakePhrase = wake.phrase,
            wakeWordState = wake.listenerState,
            hasMicPermission = hasRecordAudioPermission(),
            isTtsEnabled = snapshot.isEnabled,
            ttsEngineState = engineState,
            ttsVoices = voices,
            ttsSelectedVoiceName = snapshot.voiceName,
            ttsRate = snapshot.speechRate,
            ttsPitch = snapshot.pitch,
            porcupineAccessKey = wake.porcupineAccessKey
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(hasMicPermission = hasRecordAudioPermission())
    )

    init {
        // Kicks off engine init + populates the real voice list as soon as
        // Settings is opened, so the picker isn't empty on first look.
        tts.refreshVoices()
    }

    /** Call after the mic-permission request returns granted, then this actually turns the toggle on. */
    fun onMicPermissionGranted() {
        if (!hasRecordAudioPermission()) return
        setWakeWordEnabled(true)
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        if (enabled && !hasRecordAudioPermission()) {
            events.trySend(SettingsUiEvent.Info("Wake word on karne ke liye microphone permission allow karo."))
            return
        }
        ensureServiceRunning()
        viewModelScope.launch {
            wakeWordPreferences.setEnabled(enabled)
        }
    }

    // --- Wake word (Porcupine) --------------------------------------------

    fun setPorcupineAccessKey(accessKey: String) {
        viewModelScope.launch { wakeWordPreferences.setPorcupineAccessKey(accessKey) }
    }

    /**
     * Sends the user to the real system dialog
     * (Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) so Doze/App
     * Standby stops throttling the always-on wake-word service. Nothing is
     * granted silently — this only opens the OS's own consent screen.
     */
    fun requestBatteryOptimizationExemption() {
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            events.trySend(SettingsUiEvent.Info("Battery optimization already off SA ke liye."))
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { events.trySend(SettingsUiEvent.Info("Battery settings nahi khul paayi.")) }
    }

    // --- TTS -------------------------------------------------------------

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { ttsPreferences.setEnabled(enabled) }
    }

    fun setTtsVoice(voiceName: String?) {
        viewModelScope.launch { ttsPreferences.setVoiceName(voiceName) }
    }

    fun setTtsRate(rate: Float) {
        viewModelScope.launch { ttsPreferences.setSpeechRate(rate) }
    }

    fun setTtsPitch(pitch: Float) {
        viewModelScope.launch { ttsPreferences.setPitch(pitch) }
    }

    fun testTtsVoice() {
        val state = uiState.value
        tts.speak(
            rawText = "Yeh SA ki awaaz hai. Aisi hi sunai degi jab main reply bolunga.",
            rate = state.ttsRate,
            pitch = state.ttsPitch,
            voiceName = state.ttsSelectedVoiceName
        )
    }

    fun stopTtsSpeaking() {
        tts.stop()
    }

    private fun ensureServiceRunning() {
        val context = getApplication<Application>()
        val intent = Intent(context, AssistantForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun hasRecordAudioPermission(): Boolean {
        val context = getApplication<Application>()
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
