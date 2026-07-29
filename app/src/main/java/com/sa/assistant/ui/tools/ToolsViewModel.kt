package com.sa.assistant.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sa.assistant.core.accessibility.AccessibilityPermissionHelper
import com.sa.assistant.core.automation.AppLauncherController
import com.sa.assistant.core.automation.AutoClickController
import com.sa.assistant.core.automation.BluetoothController
import com.sa.assistant.core.automation.BrightnessController
import com.sa.assistant.core.automation.FlashlightController
import com.sa.assistant.core.automation.MusicController
import com.sa.assistant.core.automation.VolumeController
import com.sa.assistant.core.automation.social.InstagramAutomation
import com.sa.assistant.core.automation.social.WhatsAppAutomation
import com.sa.assistant.core.automation.social.YouTubeAutomation
import com.sa.assistant.data.model.AutomationResult
import com.sa.assistant.data.model.BluetoothActionOutcome
import com.sa.assistant.data.model.BrightnessLevel
import com.sa.assistant.data.model.LaunchableApp
import com.sa.assistant.data.model.MediaAction
import com.sa.assistant.data.model.VolumeLevel
import com.sa.assistant.data.model.VolumeStream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ToolsUiState(
    val volumeLevels: List<VolumeLevel> = emptyList(),
    val brightness: BrightnessLevel = BrightnessLevel(current = 128, isAutomatic = false),
    val hasBrightnessPermission: Boolean = false,
    val isFlashlightAvailable: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val hasBluetoothPermission: Boolean = true,
    val apps: List<LaunchableApp> = emptyList(),
    val appSearchQuery: String = "",
    val isLoadingApps: Boolean = true
) {
    val filteredApps: List<LaunchableApp>
        get() = if (appSearchQuery.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(appSearchQuery, ignoreCase = true) }
        }
}

/**
 * Phase 5: Accessibility Automation state for the "Automation" section of
 * [ToolsScreen]. Kept as its own [StateFlow] rather than folded into
 * [ToolsUiState]'s existing `combine` chain, so Phase 5 stays purely
 * additive and nothing about Phase 4's already-working state pipeline
 * changes.
 */
data class AutomationUiState(
    val isAccessibilityEnabled: Boolean = false,
    val isRunning: Boolean = false,
    val whatsappContact: String = "",
    val whatsappMessage: String = "",
    val youtubeQuery: String = "",
    val autoClickText: String = ""
)

/**
 * Kotlin Coroutines only ships a typed `combine` overload for up to 5
 * flows — combining 6+ flows with a typed lambda silently resolves to the
 * `combine(vararg flows: Flow<*>, transform: suspend (Array<*>) -> R)`
 * overload instead, which hands the transform an untyped `Array<Any?>`.
 * [ToolsViewModel.uiState] needs 7 sources, so the first 4 (volume,
 * brightness, flashlight, bluetooth) are pre-combined into this holder
 * using the typed 4-flow overload, then combined with the remaining 3
 * (apps, search query, loading flag) using the typed 4-flow overload
 * again. Every field stays fully typed end to end — no `Array<Any>`,
 * no unchecked casts.
 */
private data class ToolsCoreState(
    val volumeLevels: List<VolumeLevel>,
    val brightness: BrightnessLevel,
    val isFlashlightOn: Boolean,
    val isBluetoothEnabled: Boolean
)

/**
 * Backs [ToolsScreen] — Phase 4: Android Automation. Every action here
 * calls straight through to a real controller in
 * [com.sa.assistant.core.automation]; this class only shapes results
 * into [ToolsUiState] and turns permission/system-dialog needs into
 * [ToolsUiEvent]s the screen can act on. Nothing here is simulated —
 * a volume slider drag really calls [VolumeController.setPercent], a
 * flashlight toggle really calls [FlashlightController.toggle], and the
 * flashlight's on/off state in [ToolsUiState] is driven live by the
 * same [FlashlightController.isOn] flow the hardware callback updates,
 * so it can never drift from what the torch is actually doing.
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val volumeController: VolumeController,
    private val brightnessController: BrightnessController,
    private val flashlightController: FlashlightController,
    private val bluetoothController: BluetoothController,
    private val musicController: MusicController,
    private val appLauncherController: AppLauncherController,
    private val accessibilityPermissionHelper: AccessibilityPermissionHelper,
    private val whatsAppAutomation: WhatsAppAutomation,
    private val instagramAutomation: InstagramAutomation,
    private val youTubeAutomation: YouTubeAutomation,
    private val autoClickController: AutoClickController
) : ViewModel() {

    private val _volumeLevels = MutableStateFlow(volumeController.allLevels())
    private val _brightness = MutableStateFlow(brightnessController.currentLevel())
    private val _bluetoothEnabled = MutableStateFlow(bluetoothController.isEnabled())
    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    private val _appSearchQuery = MutableStateFlow("")
    private val _isLoadingApps = MutableStateFlow(true)

    private val events = Channel<ToolsUiEvent>(Channel.BUFFERED)
    val uiEvents: Flow<ToolsUiEvent> = events.receiveAsFlow()

    private val _automationState = MutableStateFlow(
        AutomationUiState(isAccessibilityEnabled = accessibilityPermissionHelper.isEnabled())
    )
    val automationState: StateFlow<AutomationUiState> = _automationState.asStateFlow()

    private val coreState: Flow<ToolsCoreState> = combine(
        _volumeLevels,
        _brightness,
        flashlightController.isOn,
        _bluetoothEnabled
    ) { volumeLevels, brightness, flashlightOn, bluetoothEnabled ->
        ToolsCoreState(
            volumeLevels = volumeLevels,
            brightness = brightness,
            isFlashlightOn = flashlightOn,
            isBluetoothEnabled = bluetoothEnabled
        )
    }

    val uiState: StateFlow<ToolsUiState> = combine(
        coreState,
        _apps,
        _appSearchQuery,
        _isLoadingApps
    ) { core, apps, query, loadingApps ->
        ToolsUiState(
            volumeLevels = core.volumeLevels,
            brightness = core.brightness,
            hasBrightnessPermission = brightnessController.hasPermission(),
            isFlashlightAvailable = flashlightController.isAvailable,
            isFlashlightOn = core.isFlashlightOn,
            isBluetoothSupported = bluetoothController.isSupported,
            isBluetoothEnabled = core.isBluetoothEnabled,
            hasBluetoothPermission = bluetoothController.hasConnectPermission(),
            apps = apps,
            appSearchQuery = query,
            isLoadingApps = loadingApps
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ToolsUiState(
            volumeLevels = _volumeLevels.value,
            brightness = _brightness.value,
            hasBrightnessPermission = brightnessController.hasPermission(),
            isFlashlightAvailable = flashlightController.isAvailable,
            isFlashlightOn = flashlightController.isOn.value,
            isBluetoothSupported = bluetoothController.isSupported,
            isBluetoothEnabled = _bluetoothEnabled.value,
            hasBluetoothPermission = bluetoothController.hasConnectPermission(),
            apps = _apps.value,
            appSearchQuery = _appSearchQuery.value,
            isLoadingApps = _isLoadingApps.value
        )
    )

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            _apps.value = appLauncherController.listLaunchableApps()
            _isLoadingApps.value = false
        }
    }

    fun onAppSearchChange(query: String) {
        _appSearchQuery.value = query
    }

    fun launchApp(packageName: String) {
        if (!appLauncherController.launch(packageName)) {
            events.trySend(ToolsUiEvent.Info("Yeh app khul nahi payi — shayad uninstall ho gayi hai."))
        }
    }

    // ---- Volume ----

    fun setVolumePercent(stream: VolumeStream, percent: Int) {
        try {
            volumeController.setPercent(stream, percent)
            _volumeLevels.value = volumeController.allLevels()
        } catch (e: SecurityException) {
            events.trySend(
                ToolsUiEvent.Info(
                    "$stream ka volume badalne ke liye \"Do Not Disturb access\" on karna hoga — Settings mein grant karo."
                )
            )
        }
    }

    fun refreshVolumeLevels() {
        _volumeLevels.value = volumeController.allLevels()
    }

    // ---- Brightness ----

    fun setBrightnessPercent(percent: Int) {
        if (!brightnessController.setPercent(percent)) {
            events.trySend(
                ToolsUiEvent.LaunchSystemIntent(
                    intent = brightnessController.permissionIntent(),
                    reason = "Brightness badalne ke liye \"Modify system settings\" permission on karo."
                )
            )
            return
        }
        _brightness.value = brightnessController.currentLevel()
    }

    fun refreshBrightness() {
        _brightness.value = brightnessController.currentLevel()
    }

    // ---- Flashlight ----

    fun toggleFlashlight() {
        if (!flashlightController.toggle()) {
            events.trySend(
                ToolsUiEvent.Info("Flashlight abhi available nahi hai (camera doosri app use kar rahi hai ya flash unit maujood nahi).")
            )
        }
    }

    // ---- Bluetooth ----

    fun toggleBluetooth() {
        val outcome = if (bluetoothController.isEnabled()) {
            bluetoothController.requestDisable()
        } else {
            bluetoothController.requestEnable()
        }
        when (outcome) {
            BluetoothActionOutcome.CHANGED -> _bluetoothEnabled.value = bluetoothController.isEnabled()
            BluetoothActionOutcome.NEEDS_PERMISSION -> events.trySend(
                ToolsUiEvent.Info("Bluetooth control karne ke liye \"Nearby devices\" permission allow karo.")
            )
            BluetoothActionOutcome.NEEDS_SYSTEM_DIALOG -> {
                val intent = if (bluetoothController.isEnabled()) {
                    bluetoothController.disableViaSettingsIntent()
                } else {
                    bluetoothController.enableViaSystemDialogIntent()
                }
                events.trySend(
                    ToolsUiEvent.LaunchSystemIntent(
                        intent = intent,
                        reason = "Android 13+ par apps Bluetooth ko seedha silently toggle nahi kar sakti — yeh system ka apna dialog hai."
                    )
                )
            }
            BluetoothActionOutcome.UNSUPPORTED -> events.trySend(
                ToolsUiEvent.Info("Is device par Bluetooth adapter hi nahi mila.")
            )
        }
    }

    fun refreshBluetoothState() {
        _bluetoothEnabled.value = bluetoothController.isEnabled()
    }

    fun onBluetoothPermissionGranted() {
        refreshBluetoothState()
    }

    // ---- Music ----

    fun sendMediaAction(action: MediaAction) {
        musicController.send(action)
    }

    // ---- Automation (Phase 5: Accessibility Automation) ----

    /** Re-reads the real Settings.Secure state — call this on resume, since it can only change while the user is away in system Settings. */
    fun refreshAccessibilityPermission() {
        _automationState.update { it.copy(isAccessibilityEnabled = accessibilityPermissionHelper.isEnabled()) }
    }

    fun openAccessibilitySettings() {
        events.trySend(
            ToolsUiEvent.LaunchSystemIntent(
                intent = accessibilityPermissionHelper.openAccessibilitySettings(),
                reason = "SA ko Accessibility list mein dhoondo aur on karo, tabhi WhatsApp/Instagram/YouTube/AutoClick automation kaam karega."
            )
        )
    }

    fun onWhatsappContactChange(value: String) {
        _automationState.update { it.copy(whatsappContact = value) }
    }

    fun onWhatsappMessageChange(value: String) {
        _automationState.update { it.copy(whatsappMessage = value) }
    }

    fun onYoutubeQueryChange(value: String) {
        _automationState.update { it.copy(youtubeQuery = value) }
    }

    fun onAutoClickTextChange(value: String) {
        _automationState.update { it.copy(autoClickText = value) }
    }

    fun sendWhatsAppMessage() {
        val contact = _automationState.value.whatsappContact.trim()
        val message = _automationState.value.whatsappMessage.trim()
        if (contact.isEmpty() || message.isEmpty()) {
            events.trySend(ToolsUiEvent.Info("Contact naam aur message dono bharo."))
            return
        }
        runAutomation { whatsAppAutomation.sendMessage(contact, message) }
    }

    fun likeInstagramFeedPost() {
        runAutomation { instagramAutomation.likeFirstFeedPost() }
    }

    fun playYoutubeSearch() {
        val query = _automationState.value.youtubeQuery.trim()
        if (query.isEmpty()) {
            events.trySend(ToolsUiEvent.Info("Pehle YouTube par kya search karna hai, woh bharo."))
            return
        }
        runAutomation { youTubeAutomation.searchAndPlay(query) }
    }

    /** Real "tap whatever matches this text on the current screen" — not scoped to any one app. */
    fun runAutoClick() {
        val text = _automationState.value.autoClickText.trim()
        if (text.isEmpty()) {
            events.trySend(ToolsUiEvent.Info("Pehle bharo ki kya click karna hai (jo text screen par dikhta hai)."))
            return
        }
        runAutomation { autoClickController.tap(text) }
    }

    private fun runAutomation(block: suspend () -> AutomationResult) {
        if (_automationState.value.isRunning) return
        viewModelScope.launch {
            _automationState.update { it.copy(isRunning = true) }
            when (val result = block()) {
                is AutomationResult.Success -> events.trySend(ToolsUiEvent.Info("Ho gaya."))
                is AutomationResult.Failed -> events.trySend(ToolsUiEvent.Info(result.reason))
                AutomationResult.AccessibilityServiceOff -> {
                    refreshAccessibilityPermission()
                    events.trySend(ToolsUiEvent.Info("Pehle Accessibility Service on karo — neeche button se."))
                }
                AutomationResult.TargetAppNotInstalled -> events.trySend(ToolsUiEvent.Info("Yeh app is device par installed nahi hai."))
            }
            _automationState.update { it.copy(isRunning = false) }
        }
    }
}
