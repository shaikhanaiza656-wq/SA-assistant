package com.sa.assistant.data.model

/**
 * A real device action the Termux server asked the phone to perform,
 * parsed from [SaResponse.action]/[SaResponse.actionParams]. This is the
 * app-side half of the "voice command -> real automation" loop shown in
 * the architecture diagram (Intent Finder -> Command Router -> Handlers)
 * — every case here maps 1:1 to a function that already exists in
 * `core/automation` (added in Phase 4/5); this file adds no new
 * automation behaviour, only a safe way to address it from a chat reply.
 *
 * [fromWire] never throws: an unrecognised or malformed action becomes
 * [AutomationCommand.Unknown] so a server typo or a future action this
 * build doesn't know about yet fails loudly in chat ("SA ye command
 * abhi nahi samajhti") instead of crashing or being silently ignored.
 *
 * Recognised wire action strings: `volume_set`, `brightness_set`,
 * `flashlight_on`/`off`/`toggle`, `music_play_pause`/`next`/`previous`,
 * `bluetooth_on`/`off`, `app_launch`, `whatsapp_send` (params: contact,
 * message), `instagram_like`, `youtube_search` (params: query), and
 * `autoclick_tap` (params: text) — the last four map onto the Phase 5
 * accessibility automations.
 */
sealed class AutomationCommand {
    data class VolumeSet(val stream: VolumeStream, val percent: Int) : AutomationCommand()
    data class BrightnessSet(val percent: Int) : AutomationCommand()
    data class FlashlightSet(val on: Boolean) : AutomationCommand()
    data object FlashlightToggle : AutomationCommand()
    data class MusicSend(val action: MediaAction) : AutomationCommand()
    data class BluetoothSet(val on: Boolean) : AutomationCommand()
    data class AppLaunch(val packageName: String) : AutomationCommand()

    // Phase 5 bridge: same real WhatsApp/Instagram/YouTube/AutoClick
    // automations that ToolsScreen already drives from manual taps
    // (core/automation/social/* and AutoClickController) — these four
    // cases route the exact same suspend functions through this
    // voice/chat command loop instead of requiring the user to open the
    // Tools tab and press a button.
    data class WhatsAppSend(val contact: String, val message: String) : AutomationCommand()
    data object InstagramLike : AutomationCommand()
    data class YouTubeSearch(val query: String) : AutomationCommand()
    /** Generic "tap whatever on screen right now matches this text" — not scoped to one app. */
    data class AutoClickTap(val text: String) : AutomationCommand()

    data class Unknown(val rawAction: String) : AutomationCommand()

    companion object {
        fun fromWire(action: String, params: Map<String, String>): AutomationCommand {
            val percent = params["percent"]?.toIntOrNull()
            return when (action) {
                "volume_set" -> {
                    val stream = when (params["stream"]) {
                        "ring" -> VolumeStream.RING
                        "alarm" -> VolumeStream.ALARM
                        "notification" -> VolumeStream.NOTIFICATION
                        "call" -> VolumeStream.VOICE_CALL
                        else -> VolumeStream.MUSIC
                    }
                    if (percent == null) Unknown(action) else VolumeSet(stream, percent)
                }
                "brightness_set" -> if (percent == null) Unknown(action) else BrightnessSet(percent)
                "flashlight_on" -> FlashlightSet(true)
                "flashlight_off" -> FlashlightSet(false)
                "flashlight_toggle" -> FlashlightToggle
                "music_play_pause" -> MusicSend(MediaAction.PLAY_PAUSE)
                "music_next" -> MusicSend(MediaAction.NEXT)
                "music_previous" -> MusicSend(MediaAction.PREVIOUS)
                "bluetooth_on" -> BluetoothSet(true)
                "bluetooth_off" -> BluetoothSet(false)
                "app_launch" -> {
                    val pkg = params["package"]
                    if (pkg.isNullOrBlank()) Unknown(action) else AppLaunch(pkg)
                }
                "whatsapp_send" -> {
                    val contact = params["contact"]
                    val message = params["message"]
                    if (contact.isNullOrBlank() || message.isNullOrBlank()) Unknown(action)
                    else WhatsAppSend(contact, message)
                }
                "instagram_like" -> InstagramLike
                "youtube_search" -> {
                    val query = params["query"]
                    if (query.isNullOrBlank()) Unknown(action) else YouTubeSearch(query)
                }
                "autoclick_tap" -> {
                    val text = params["text"]
                    if (text.isNullOrBlank()) Unknown(action) else AutoClickTap(text)
                }
                else -> Unknown(action)
            }
        }
    }
}
