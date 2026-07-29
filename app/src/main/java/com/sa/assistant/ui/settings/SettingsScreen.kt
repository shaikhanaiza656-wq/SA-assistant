package com.sa.assistant.ui.settings

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.sa.assistant.data.model.TtsEngineState
import com.sa.assistant.data.model.TtsVoiceOption
import com.sa.assistant.data.model.TTS_PITCH_MAX
import com.sa.assistant.data.model.TTS_PITCH_MIN
import com.sa.assistant.data.model.TTS_RATE_MAX
import com.sa.assistant.data.model.TTS_RATE_MIN
import com.sa.assistant.data.model.WakeWordState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 6 Part 1 (Wake Word) + Part 2 (TTS switching). Both cards are real:
 * the wake-word switch and status mirror the live background listener (see
 * [SettingsViewModel]), and the TTS card's voice list is exactly what
 * [com.sa.assistant.core.tts.SaTextToSpeech] reports from the device's own
 * engine — nothing hardcoded. Voice verification, Whisper STT, and memory
 * land here in Phase 6 Part 3 onward.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val micPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is SettingsUiEvent.Info -> scope.launch { snackbarHostState.showSnackbar(event.message) }
            }
        }
    }

    LaunchedEffect(micPermissionState.status) {
        if (micPermissionState.status.isGranted) {
            viewModel.onMicPermissionGranted()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            WakeWordCard(
                wakePhrase = state.wakePhrase,
                isEnabled = state.isWakeWordEnabled,
                wakeWordState = state.wakeWordState,
                onToggle = { checked ->
                    if (checked && !micPermissionState.status.isGranted) {
                        micPermissionState.launchPermissionRequest()
                    } else {
                        viewModel.setWakeWordEnabled(checked)
                    }
                }
            )

            TtsCard(
                isEnabled = state.isTtsEnabled,
                engineState = state.ttsEngineState,
                voices = state.ttsVoices,
                selectedVoiceName = state.ttsSelectedVoiceName,
                rate = state.ttsRate,
                pitch = state.ttsPitch,
                onToggle = viewModel::setTtsEnabled,
                onVoiceSelected = viewModel::setTtsVoice,
                onRateChange = viewModel::setTtsRate,
                onPitchChange = viewModel::setTtsPitch,
                onTest = viewModel::testTtsVoice,
                onStop = viewModel::stopTtsSpeaking
            )
        }
    }
}

@Composable
private fun WakeWordCard(
    wakePhrase: String,
    isEnabled: Boolean,
    wakeWordState: WakeWordState,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Wake Word", style = MaterialTheme.typography.titleMedium)
                    Text("\"$wakePhrase\" bol kar SA ko jagao", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = isEnabled, onCheckedChange = onToggle)
            }
            Text(
                text = wakeWordStatusText(isEnabled, wakeWordState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun wakeWordStatusText(isEnabled: Boolean, state: WakeWordState): String {
    if (!isEnabled) return "Off — background mein \"SA\" ke liye sun nahi raha."
    return when (state) {
        WakeWordState.LISTENING -> "Chalu hai — background mein \"SA\" sun raha hai."
        WakeWordState.MIC_PERMISSION_REQUIRED -> "Microphone permission chahiye — abhi sun nahi paa raha."
        WakeWordState.RECOGNIZER_UNAVAILABLE -> "Is device par koi speech recognizer nahi mila — sun nahi paa raha."
        WakeWordState.ERROR -> "Listener restart ho raha hai (network ya recognizer glitch)."
        WakeWordState.IDLE -> "On ho raha hai..."
    }
}

@Composable
private fun TtsCard(
    isEnabled: Boolean,
    engineState: TtsEngineState,
    voices: List<TtsVoiceOption>,
    selectedVoiceName: String?,
    rate: Float,
    pitch: Float,
    onToggle: (Boolean) -> Unit,
    onVoiceSelected: (String?) -> Unit,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onTest: () -> Unit,
    onStop: () -> Unit
) {
    var voiceMenuOpen by remember { mutableStateOf(false) }
    val selectedLabel = voices.firstOrNull { it.name == selectedVoiceName }?.displayLabel()
        ?: "Device default"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Voice Replies (TTS)", style = MaterialTheme.typography.titleMedium)
                    Text("SA ka reply khatam hote hi zor se bol kar bhi sunayega", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = isEnabled, onCheckedChange = onToggle)
            }

            Text(
                text = ttsStatusText(engineState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (engineState != TtsEngineState.UNAVAILABLE) {
                Column {
                    Text("Voice", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { voiceMenuOpen = true }) {
                            Text(selectedLabel)
                        }
                        DropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Device default") },
                                onClick = { onVoiceSelected(null); voiceMenuOpen = false }
                            )
                            voices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text(voice.displayLabel()) },
                                    onClick = { onVoiceSelected(voice.name); voiceMenuOpen = false }
                                )
                            }
                        }
                    }
                }

                Column {
                    Text("Speed: ${"%.1f".format(rate)}x", style = MaterialTheme.typography.labelLarge)
                    Slider(value = rate, onValueChange = onRateChange, valueRange = TTS_RATE_MIN..TTS_RATE_MAX)
                }

                Column {
                    Text("Pitch: ${"%.1f".format(pitch)}", style = MaterialTheme.typography.labelLarge)
                    Slider(value = pitch, onValueChange = onPitchChange, valueRange = TTS_PITCH_MIN..TTS_PITCH_MAX)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTest) { Text("Test") }
                    TextButton(
                        onClick = onStop,
                        enabled = engineState == TtsEngineState.SPEAKING
                    ) { Text("Stop") }
                }
            }
        }
    }
}

private fun ttsStatusText(state: TtsEngineState): String = when (state) {
    TtsEngineState.INITIALIZING -> "TTS engine load ho raha hai..."
    TtsEngineState.READY -> "Ready."
    TtsEngineState.SPEAKING -> "Bol raha hai..."
    TtsEngineState.UNAVAILABLE -> "Is device par koi TTS engine install nahi hai — voice replies kaam nahi karenge."
}
