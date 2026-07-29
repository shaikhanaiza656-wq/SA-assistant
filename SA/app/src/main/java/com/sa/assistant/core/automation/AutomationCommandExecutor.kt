package com.sa.assistant.core.automation

import com.sa.assistant.core.accessibility.AccessibilityPermissionHelper
import com.sa.assistant.core.automation.social.InstagramAutomation
import com.sa.assistant.core.automation.social.WhatsAppAutomation
import com.sa.assistant.core.automation.social.YouTubeAutomation
import com.sa.assistant.data.model.AutomationCommand
import com.sa.assistant.data.model.AutomationResult
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
 * [BluetoothController], [AppLauncherController], [WhatsAppAutomation],
 * [InstagramAutomation], [YouTubeAutomation], [AutoClickController]) that
 * [ToolsScreen] already drives from manual taps — nothing here is a
 * second, parallel implementation.
 *
 * Every branch returns a short, honest Hinglish status line: real
 * success ("Volume 50% kar diya"), a real specific failure ("Brightness
 * permission nahi mila"), or — for cases Android itself won't let an app
 * finish silently (Bluetooth on Android 13+, brightness's special
 * permission, or Accessibility Service not turned on) — a real system
 * dialog/settings [Intent] the caller can launch, exactly like
 * [ToolsViewModel] already does. Nothing is ever reported as done when
 * it wasn't.
 *
 * [execute] is `suspend` because the four Phase 5 accessibility
 * automations ([WhatsAppSend]/[InstagramLike]/[YouTubeSearch]/
 * [AutoClickTap]) drive a live node tree with real waits between steps
 * (see each automation's own delays) — both call sites
 * ([com.sa.assistant.ui.chat.ChatViewModel] and
 * [com.sa.assistant.core.assistant.AssistantForegroundService]) already
 * call this from inside a coroutine, so this is a non-breaking change.
 */
@Singleton
class AutomationCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val volumeController: VolumeController,
    private val brightnessController: BrightnessController,
    private val flashlightController: FlashlightController,
    private val musicController: MusicController,
    private val bluetoothController: BluetoothController,
    private val appLauncherController: AppLauncherController,
    private val whatsAppAutomation: WhatsAppAutomation,
    private val instagramAutomation: InstagramAutomation,
    private val youTubeAutomation: YouTubeAutomation,
    private val autoClickController: AutoClickController,
    private val accessibilityPermissionHelper: AccessibilityPermissionHelper
) {
    /** Outcome of running one command: a status line, and an optional real system Intent the UI must launch. */
    data class ExecutionOutcome(val message: String, val followUpIntent: Intent? = null)

    suspend fun execute(command: AutomationCommand): ExecutionOutcome = when (command) {
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

        is AutomationCommand.WhatsAppSend ->
            toOutcome(
                whatsAppAutomation.sendMessage(command.contact, command.message),
                successMessage = "WhatsApp par \"${command.contact}\" ko message bhej diya.",
                targetAppLabel = "WhatsApp"
            )

        AutomationCommand.InstagramLike ->
            toOutcome(
                instagramAutomation.likeFirstFeedPost(),
                successMessage = "Instagram feed ka pehla post like kar diya.",
                targetAppLabel = "Instagram"
            )

        is AutomationCommand.YouTubeSearch ->
            toOutcome(
                youTubeAutomation.searchAndPlay(command.query),
                successMessage = "YouTube par \"${command.query}\" search karke pehla result khol diya.",
                targetAppLabel = "YouTube"
            )

        is AutomationCommand.AutoClickTap ->
            toOutcome(
                autoClickController.tap(command.text),
                successMessage = "\"${command.text}\" par click kar diya.",
                targetAppLabel = null
            )

        is AutomationCommand.Unknown ->
            ExecutionOutcome("SA ye command (\"${command.rawAction}\") abhi nahi samajhti.")
    }

    /**
     * Shared mapping from a Phase 5 automation's real [AutomationResult]
     * to the same honest [ExecutionOutcome] shape every other branch
     * above returns — including sending the user to the real
     * Accessibility Settings screen (never a silent no-op) when the
     * service isn't on yet.
     */
    private fun toOutcome(
        result: AutomationResult,
        successMessage: String,
        targetAppLabel: String?
    ): ExecutionOutcome = when (result) {
        AutomationResult.Success -> ExecutionOutcome(successMessage)
        is AutomationResult.Failed -> ExecutionOutcome(result.reason)
        AutomationResult.AccessibilityServiceOff -> ExecutionOutcome(
            "Pehle SA ki Accessibility Service on karo, tabhi yeh command chalega — system Settings khol raha hoon.",
            followUpIntent = accessibilityPermissionHelper.openAccessibilitySettings()
        )
        AutomationResult.TargetAppNotInstalled -> ExecutionOutcome(
            if (targetAppLabel != null) "\"$targetAppLabel\" is device par installed nahi hai."
            else "Yeh app is device par installed nahi hai."
        )
    }
}
