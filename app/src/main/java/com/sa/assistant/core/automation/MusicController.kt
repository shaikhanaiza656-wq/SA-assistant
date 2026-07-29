package com.sa.assistant.core.automation

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.sa.assistant.data.model.MediaAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real media transport control via
 * [AudioManager.dispatchMediaKeyEvent] — the exact same system
 * mechanism a Bluetooth headset or wired-earphone button uses.
 * Whichever app currently owns the active media session (Spotify,
 * YouTube Music, the phone's own player, a podcast app...) receives the
 * event and reacts; SA needs no `MediaSessionManager`/notification-
 * listener permission and no special-cased integration per app, because
 * every media app already listens for this.
 *
 * Honest limitation: if nothing is currently playing or paused
 * anywhere, PLAY_PAUSE has nothing to resume — same as pressing a
 * headset button with no app open. This is a real dispatch, not a
 * simulated tap on a specific app's UI.
 */
@Singleton
class MusicController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun send(action: MediaAction) {
        val eventTime = System.currentTimeMillis()
        val down = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, action.keyCode, 0)
        val up = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, action.keyCode, 0)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
    }
}
