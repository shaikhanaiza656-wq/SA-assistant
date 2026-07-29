package com.sa.assistant.ui.settings

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sa.assistant.core.assistant.AssistantForegroundService
import com.sa.assistant.core.wakeword.WakeWordListener
import com.sa.assistant.core.wakeword.WakeWordPreferences
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

data class SettingsUiState(
    val isWakeWordEnabled: Boolean = false,
    val wakePhrase: String = "SA",
    val wakeWordState: WakeWordState = WakeWordState.IDLE,
    val hasMicPermission: Boolean = false
)

/**
 * Backs [SettingsScreen] — Phase 6 Part 1: Wake Word. The toggle here is the
 * single source of truth ([WakeWordPreferences]); [AssistantForegroundService]
 * is the thing that actually starts/stops [WakeWordListener] in the
 * background, this ViewModel only starts that service the first time
 * (`ContextCompat.startForegroundService`) and otherwise just persists the
 * preference and mirrors the listener's live state for display.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val wakeWordPreferences: WakeWordPreferences,
    private val wakeWordListener: WakeWordListener
) : AndroidViewModel(application) {

    private val events = Channel<SettingsUiEvent>(Channel.BUFFERED)
    val uiEvents: Flow<SettingsUiEvent> = events.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        wakeWordPreferences.isEnabled,
        wakeWordPreferences.wakePhrase,
        wakeWordListener.state
    ) { enabled, phrase, listenerState ->
        SettingsUiState(
            isWakeWordEnabled = enabled,
            wakePhrase = phrase,
            wakeWordState = listenerState,
            hasMicPermission = hasRecordAudioPermission()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(hasMicPermission = hasRecordAudioPermission())
    )

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
