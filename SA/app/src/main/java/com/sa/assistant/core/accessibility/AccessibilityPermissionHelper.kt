package com.sa.assistant.core.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real accessibility-permission check — reads the same
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` string Android itself
 * consults, rather than trusting [SaAccessibilityService.isRunning]
 * alone (that flag can lag a moment right after the user flips the
 * switch in system Settings and returns to the app).
 */
@Singleton
class AccessibilityPermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** True only if the user has actually enabled SaAccessibilityService in system Settings right now. */
    fun isEnabled(): Boolean {
        val expectedComponent = "${context.packageName}/${SaAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(':').any { entry ->
            !TextUtils.isEmpty(entry) && entry.equals(expectedComponent, ignoreCase = true)
        }
    }

    /**
     * Android does not let an app silently turn on its own accessibility
     * service — unlike the WRITE_SETTINGS-style "special" permission
     * flow in [com.sa.assistant.core.automation.BrightnessController],
     * this is a hard block by design, because a connected accessibility
     * service can read and act on the entire screen. So this only opens
     * the real system Settings screen; the user must flip the toggle
     * themselves, and this app never claims otherwise.
     */
    fun openAccessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
