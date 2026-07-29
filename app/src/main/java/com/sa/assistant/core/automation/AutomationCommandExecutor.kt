package com.sa.assistant.core.automation

import com.sa.assistant.data.model.AutomationCommand
import com.sa.assistant.data.model.BluetoothActionOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs an [AutomationCommand] that arrived from the Termux server inside
 * a chat reply, using the exact same real controllers ([VolumeController],
 * [BrightnessController], [FlashlightController], [MusicController],
 * [BluetoothController], [AppLauncherController]) that [ToolsScreen]
 * already drives from manual taps — nothing here is a second, parallel
 * implementation.
 *
 * Every branch returns a short, honest Hinglish status line: real
 * success ("Volume 50% kar diya"), a real specific failure ("Brightness
 * permission nahi mila"), or — for cases Android itself won't let an app
 * finish silently (Bluetooth on Android 13+, brightness's special
 * permission) — a real system dialog/settings [Intent] the caller can
 * launch, exactly like [ToolsViewModel] already does. Nothing is ever
 * reported as done when it wasn't.
 */
@Singleton
class AutomationCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val volumeController: VolumeController,
    private val brightnessController: BrightnessController,
    private val flashlightController: FlashlightController,
    private val musicController: MusicController,
    private val bluetoothController: BluetoothController,
    private val appLauncherController: AppLauncherController
) {
    /** Outcome of running one command: a status line, and an optional real system Intent the UI must launch. */
    data class ExecutionOutcome(val message: String, val followUpIntent: Intent? = null)

    fun execute(command: AutomationCommand): ExecutionOutcome = when (command) {
        is AutomationCommand.VolumeSet -> {
            try {
                volumeController.setPercent(command.stream, command.percent)
                ExecutionOutcome("Volume ${command.percent}% kar diya.")
            } catch (e: SecurityException) {
                ExecutionOutcome("Is stream ka volume badalne ke liye Do Not Disturb access chahiye — Settings mein grant karo.")
            }
        }

        is AutomationCommand.BrightnessSet -> {
            if (!brightnessController.hasPermission()) {
                ExecutionOutcome(
                    "Brightness badalne ke liye \"Modify system settings\" permission chahiye.",
                    followUpIntent = brightnessController.permissionIntent()
                )
            } else {
                brightnessController.setPercent(command.percent)
                ExecutionOutcome("Brightness ${command.percent}% kar diya.")
            }
        }

        is AutomationCommand.FlashlightSet -> {
            val changed = flashlightController.setOn(command.on)
            if (changed) ExecutionOutcome(if (command.on) "Flashlight on kar diya." else "Flashlight off kar diya.")
            else ExecutionOutcome("Flashlight badal nahi paya — camera doosri app use kar rahi ho sakti hai, ya flash unit nahi hai.")
        }

        AutomationCommand.FlashlightToggle -> {
            val changed = flashlightController.toggle()
            if (changed) ExecutionOutcome("Flashlight toggle kar diya.")
            else ExecutionOutcome("Flashlight toggle nahi ho paya.")
        }

        is AutomationCommand.MusicSend -> {
            musicController.send(command.action)
            ExecutionOutcome("Music command bhej diya.")
        }

        is AutomationCommand.BluetoothSet -> {
            val outcome = if (command.on) bluetoothController.requestEnable() else bluetoothController.requestDisable()
            when (outcome) {
                BluetoothActionOutcome.CHANGED ->
                    ExecutionOutcome(if (command.on) "Bluetooth on kar diya." else "Bluetooth off kar diya.")
                BluetoothActionOutcome.NEEDS_PERMISSION ->
                    ExecutionOutcome("Bluetooth control ke liye permission chahiye.")
                BluetoothActionOutcome.NEEDS_SYSTEM_DIALOG ->
                    ExecutionOutcome(
                        "Android 13+ par apps seedha Bluetooth on nahi kar sakti — system dialog khol raha hoon.",
                        followUpIntent = if (command.on) bluetoothController.enableViaSystemDialogIntent()
                                         else bluetoothController.disableViaSettingsIntent()
                    )
                BluetoothActionOutcome.UNSUPPORTED ->
                    ExecutionOutcome("Is device par Bluetooth adapter nahi mila.")
            }
        }

        is AutomationCommand.AppLaunch -> {
            if (appLauncherController.launch(command.packageName)) ExecutionOutcome("App khol diya.")
            else ExecutionOutcome("\"${command.packageName}\" launch nahi ho paya — installed nahi hai shayad.")
        }

        is AutomationCommand.Unknown ->
            ExecutionOutcome("SA ye command (\"${command.rawAction}\") abhi nahi samajhti.")
    }
}
