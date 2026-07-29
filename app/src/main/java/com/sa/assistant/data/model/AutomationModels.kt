package com.sa.assistant.data.model

import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Phase 4: Android Automation — shared model types for
 * [com.sa.assistant.core.automation]. Kept in `data/model` like every
 * other phase's models ([PdfModels], [ChatMessage]) instead of inline
 * inside the controllers, so the controllers stay focused on doing the
 * real system calls.
 */

/** The five system audio streams a phone actually has independent volume for. */
enum class VolumeStream(val internal: Int) {
    MUSIC(AudioManager.STREAM_MUSIC),
    RING(AudioManager.STREAM_RING),
    ALARM(AudioManager.STREAM_ALARM),
    NOTIFICATION(AudioManager.STREAM_NOTIFICATION),
    VOICE_CALL(AudioManager.STREAM_VOICE_CALL)
}

/** Real current/max reading for one [VolumeStream] — never guessed, always read live off [AudioManager]. */
data class VolumeLevel(val stream: VolumeStream, val current: Int, val max: Int) {
    val percent: Int get() = if (max <= 0) 0 else ((current * 100) / max).coerceIn(0, 100)
}

/** Real current brightness read off Settings.System — 0..255 is the actual Android scale, not invented. */
data class BrightnessLevel(val current: Int, val max: Int = 255, val isAutomatic: Boolean) {
    val percent: Int get() = ((current * 100) / max).coerceIn(0, 100)
}

/** Media transport buttons, dispatched as real system media-key events — same as a headset button. */
enum class MediaAction(val keyCode: Int) {
    PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    NEXT(KeyEvent.KEYCODE_MEDIA_NEXT),
    PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
    STOP(KeyEvent.KEYCODE_MEDIA_STOP)
}

/** One installed, launchable app — found the same way the device's own home-screen app drawer does. */
data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

/**
 * Honest outcome of a Bluetooth on/off attempt. Android 13+ blocks apps
 * from silently toggling Bluetooth (adapter.enable()/disable() are
 * deprecated and are no-ops for apps targeting API 33+), so a direct
 * toggle is not guaranteed — the UI uses this to decide whether to show
 * a real system dialog/settings screen instead of pretending it always
 * works silently.
 */
enum class BluetoothActionOutcome { CHANGED, NEEDS_PERMISSION, NEEDS_SYSTEM_DIALOG, UNSUPPORTED }
