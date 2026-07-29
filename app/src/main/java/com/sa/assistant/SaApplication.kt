package com.sa.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * @HiltAndroidApp generates the Hilt component that every ViewModel,
 * Service, and Repository in the app hangs off of, so this class stays
 * intentionally thin: dependency wiring lives in the [core.di] modules,
 * not here.
 */
@HiltAndroidApp
class SaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    /**
     * The assistant's wake-word listener runs as a foreground service
     * (Phase 1), which on Android 8+ requires a notification channel to
     * exist before the service starts, or startForeground() throws.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ASSISTANT_CHANNEL_ID,
                "SA Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Wake word listening and voice session status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ASSISTANT_CHANNEL_ID = "sa_assistant_channel"
    }
}
