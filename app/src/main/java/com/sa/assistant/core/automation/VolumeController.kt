package com.sa.assistant.core.automation

import android.content.Context
import android.media.AudioManager
import com.sa.assistant.data.model.VolumeLevel
import com.sa.assistant.data.model.VolumeStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real volume control via [AudioManager] — every read here is the
 * actual current/max index for that stream on this device right now,
 * and every write is a real `setStreamVolume`/`adjustStreamVolume`
 * call, not a value held in memory that pretends to reflect the system.
 *
 * Honest limitation: on API 24+, changing [VolumeStream.RING] or
 * [VolumeStream.NOTIFICATION] while the app does not hold "Do Not
 * Disturb access" throws [SecurityException] — Android reserves those
 * two streams for the DND policy owner. [ToolsViewModel] catches this
 * and sends the user to grant that access rather than swallowing the
 * failure silently.
 */
@Singleton
class VolumeController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun currentLevel(stream: VolumeStream): VolumeLevel {
        val current = audioManager.getStreamVolume(stream.internal)
        val max = audioManager.getStreamMaxVolume(stream.internal)
        return VolumeLevel(stream, current, max)
    }

    fun allLevels(): List<VolumeLevel> = VolumeStream.entries.map { currentLevel(it) }

    /**
     * Sets an absolute index (0..stream max), clamped to the real range
     * for that stream. Throws [SecurityException] for RING/NOTIFICATION
     * without DND access — left to propagate so the caller can react.
     */
    @Throws(SecurityException::class)
    fun setVolume(stream: VolumeStream, index: Int, showUi: Boolean = false) {
        val max = audioManager.getStreamMaxVolume(stream.internal)
        val clamped = index.coerceIn(0, max)
        val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
        audioManager.setStreamVolume(stream.internal, clamped, flags)
    }

    @Throws(SecurityException::class)
    fun setPercent(stream: VolumeStream, percent: Int) {
        val max = audioManager.getStreamMaxVolume(stream.internal)
        val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt()
        setVolume(stream, target)
    }
}
