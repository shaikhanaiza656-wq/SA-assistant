package com.sa.assistant.ui.settings

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.sa.assistant.data.model.WakeWordState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 6 Part 1: Wake Word. The only control here so far is real — the
 * switch is bound straight through [SettingsViewModel] to
 * [com.sa.assistant.core.wakeword.WakeWordPreferences], and the status line
 * mirrors the live [WakeWordState] of the actual background listener, not a
 * guess. Voice verification, Whisper STT, TTS switching, and memory land
 * here in Phase 6 Parts 2 and 3.
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
