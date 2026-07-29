package com.sa.assistant.ui.tools

import android.Manifest
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.sa.assistant.data.model.LaunchableApp
import com.sa.assistant.data.model.MediaAction
import com.sa.assistant.data.model.VolumeLevel
import com.sa.assistant.data.model.VolumeStream
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 4: Android Automation. Every control on this screen is wired to
 * a real system call in [com.sa.assistant.core.automation] via
 * [ToolsViewModel] — there is no placeholder tool here.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ToolsScreen(viewModel: ToolsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val automationState by viewModel.automationState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val bluetoothPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberPermissionState(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        null
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is ToolsUiEvent.Info -> scope.launch { snackbarHostState.showSnackbar(event.message) }
                is ToolsUiEvent.LaunchSystemIntent -> {
                    scope.launch { snackbarHostState.showSnackbar(event.reason) }
                    context.startActivity(event.intent)
                }
            }
        }
    }

    LaunchedEffect(bluetoothPermissionState?.status) {
        if (bluetoothPermissionState?.status?.isGranted == true) {
            viewModel.onBluetoothPermissionGranted()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { SectionTitle("Volume") }
            items(state.volumeLevels, key = { it.stream }) { level ->
                VolumeRow(level = level, onChange = { percent -> viewModel.setVolumePercent(level.stream, percent) })
            }

            item { Divider() }

            item { SectionTitle("Brightness") }
            item {
                BrightnessRow(
                    percent = state.brightness.percent,
                    hasPermission = state.hasBrightnessPermission,
                    onChange = { viewModel.setBrightnessPercent(it) }
                )
            }

            item { Divider() }

            item { SectionTitle("Flashlight & Bluetooth") }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                if (state.isFlashlightOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                                contentDescription = "Flashlight"
                            )
                            Switch(
                                checked = state.isFlashlightOn,
                                enabled = state.isFlashlightAvailable,
                                onCheckedChange = { viewModel.toggleFlashlight() }
                            )
                        }
                    }
                    Card(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                if (state.isBluetoothEnabled) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
                                contentDescription = "Bluetooth"
                            )
                            Switch(
                                checked = state.isBluetoothEnabled,
                                enabled = state.isBluetoothSupported,
                                onCheckedChange = {
                                    if (bluetoothPermissionState != null && !bluetoothPermissionState.status.isGranted) {
                                        bluetoothPermissionState.launchPermissionRequest()
                                    } else {
                                        viewModel.toggleBluetooth()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            item { Divider() }

            item { SectionTitle("Music") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(onClick = { viewModel.sendMediaAction(MediaAction.PREVIOUS) }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    IconButton(onClick = { viewModel.sendMediaAction(MediaAction.PLAY_PAUSE) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play/Pause")
                    }
                    IconButton(onClick = { viewModel.sendMediaAction(MediaAction.NEXT) }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                }
            }

            item { Divider() }

            item { SectionTitle("Apps kholo") }
            item {
                OutlinedTextField(
                    value = state.appSearchQuery,
                    onValueChange = viewModel::onAppSearchChange,
                    label = { Text("App search karo") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (state.isLoadingApps) {
                item { Text("Installed apps load ho rahe hain…") }
            } else {
                items(state.filteredApps, key = { it.packageName }) { app ->
                    AppRow(app = app, onClick = { viewModel.launchApp(app.packageName) })
                }
            }

            item { Divider() }

            item { SectionTitle("Automation (WhatsApp / Instagram / YouTube / AutoClick)") }
            item {
                AccessibilityPermissionCard(
                    isEnabled = automationState.isAccessibilityEnabled,
                    onOpenSettings = { viewModel.openAccessibilitySettings() }
                )
            }
            item {
                WhatsAppAutomationCard(
                    contact = automationState.whatsappContact,
                    message = automationState.whatsappMessage,
                    enabled = automationState.isAccessibilityEnabled && !automationState.isRunning,
                    isRunning = automationState.isRunning,
                    onContactChange = viewModel::onWhatsappContactChange,
                    onMessageChange = viewModel::onWhatsappMessageChange,
                    onSend = { viewModel.sendWhatsAppMessage() }
                )
            }
            item {
                InstagramAutomationCard(
                    enabled = automationState.isAccessibilityEnabled && !automationState.isRunning,
                    isRunning = automationState.isRunning,
                    onLike = { viewModel.likeInstagramFeedPost() }
                )
            }
            item {
                YouTubeAutomationCard(
                    query = automationState.youtubeQuery,
                    enabled = automationState.isAccessibilityEnabled && !automationState.isRunning,
                    isRunning = automationState.isRunning,
                    onQueryChange = viewModel::onYoutubeQueryChange,
                    onPlay = { viewModel.playYoutubeSearch() }
                )
            }
            item {
                AutoClickCard(
                    text = automationState.autoClickText,
                    enabled = automationState.isAccessibilityEnabled && !automationState.isRunning,
                    isRunning = automationState.isRunning,
                    onTextChange = viewModel::onAutoClickTextChange,
                    onTap = { viewModel.runAutoClick() }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun VolumeRow(level: VolumeLevel, onChange: (Int) -> Unit) {
    var localPercent by remember(level.stream) { mutableFloatStateOf(level.percent.toFloat()) }
    LaunchedEffect(level.percent) { localPercent = level.percent.toFloat() }

    Column {
        Text(text = streamLabel(level.stream), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = localPercent,
            onValueChange = { localPercent = it },
            onValueChangeFinished = { onChange(localPercent.toInt()) },
            valueRange = 0f..100f
        )
    }
}

private fun streamLabel(stream: VolumeStream): String = when (stream) {
    VolumeStream.MUSIC -> "Media"
    VolumeStream.RING -> "Ringtone"
    VolumeStream.ALARM -> "Alarm"
    VolumeStream.NOTIFICATION -> "Notification"
    VolumeStream.VOICE_CALL -> "Call"
}

@Composable
private fun BrightnessRow(percent: Int, hasPermission: Boolean, onChange: (Int) -> Unit) {
    var localPercent by remember { mutableFloatStateOf(percent.toFloat()) }
    LaunchedEffect(percent) { localPercent = percent.toFloat() }

    Column {
        Slider(
            value = localPercent,
            onValueChange = { localPercent = it },
            onValueChangeFinished = { onChange(localPercent.toInt()) },
            valueRange = 0f..100f
        )
        if (!hasPermission) {
            Text(
                text = "Pehli baar brightness badalne par \"Modify system settings\" permission maangi jaayegi.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Real accessibility-permission status card. There is no toggle here on
 * purpose — Android does not let this app turn its own Accessibility
 * Service on, so tapping the button only opens the real system Settings
 * screen; [ToolsScreen]'s lifecycle observer re-checks the real state
 * the moment the user comes back.
 */
@Composable
private fun AccessibilityPermissionCard(isEnabled: Boolean, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isEnabled) {
                    "Accessibility Service ON hai"
                } else {
                    "Accessibility Service abhi OFF hai — automation kaam nahi karega"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            if (!isEnabled) {
                OutlinedButton(onClick = onOpenSettings) { Text("On karo") }
            }
        }
    }
}

@Composable
private fun WhatsAppAutomationCard(
    contact: String,
    message: String,
    enabled: Boolean,
    isRunning: Boolean,
    onContactChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("WhatsApp message bhejo", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = contact,
                onValueChange = onContactChange,
                label = { Text("Contact ka naam") },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                label = { Text("Message") },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            AutomationRunButton(label = "Bhejo", enabled = enabled, isRunning = isRunning, onClick = onSend)
        }
    }
}

@Composable
private fun InstagramAutomationCard(enabled: Boolean, isRunning: Boolean, onLike: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Instagram feed ka pehla post like karo", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            AutomationRunButton(label = "Like karo", enabled = enabled, isRunning = isRunning, onClick = onLike)
        }
    }
}

@Composable
private fun YouTubeAutomationCard(
    query: String,
    enabled: Boolean,
    isRunning: Boolean,
    onQueryChange: (String) -> Unit,
    onPlay: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("YouTube par search karke pehla result khelo", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Kya search karna hai") },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            AutomationRunButton(label = "Play karo", enabled = enabled, isRunning = isRunning, onClick = onPlay)
        }
    }
}

@Composable
private fun AutoClickCard(
    text: String,
    enabled: Boolean,
    isRunning: Boolean,
    onTextChange: (String) -> Unit,
    onTap: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                "Jo bhi abhi screen par khula hai, usme yeh text dhoondh ke tap karo",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text("Kya click karna hai (jo text screen par dikhta hai)") },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            AutomationRunButton(label = "Click karo", enabled = enabled, isRunning = isRunning, onClick = onTap)
        }
    }
}

@Composable
private fun AutomationRunButton(label: String, enabled: Boolean, isRunning: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        if (isRunning) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun AppRow(app: LaunchableApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        app.icon?.let { drawable ->
            Image(
                bitmap = drawable.toBitmap(width = 96, height = 96).asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier.size(36.dp)
            )
        } ?: Spacer(modifier = Modifier.size(36.dp))
        Text(text = app.label, modifier = Modifier.weight(1f))
        IconButton(onClick = onClick) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Kholo")
        }
    }
}
