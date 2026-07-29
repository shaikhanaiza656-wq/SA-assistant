package com.sa.assistant.core.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.sa.assistant.data.model.BrightnessLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real screen-brightness control via `Settings.System`. Android treats
 * this as a special "modify system settings" permission — there is no
 * runtime-permission popup for it; the user has to flip it on for this
 * app in a dedicated system screen. Rather than silently failing (or
 * worse, pretending success), [setBrightness] returns `false` when that
 * permission is missing so the UI can send the user to
 * [permissionIntent] and explain why.
 */
@Singleton
class BrightnessController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasPermission(): Boolean = Settings.System.canWrite(context)

    /** Real system screen — "Modify system settings" — the only way to grant this permission. */
    fun permissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun currentLevel(): BrightnessLevel {
        val resolver = context.contentResolver
        val current = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            128
        }
        val mode = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        } catch (e: Settings.SettingNotFoundException) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        return BrightnessLevel(
            current = current,
            isAutomatic = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        )
    }

    /**
     * Writes a real brightness value (0..255). Switches auto-brightness
     * off first — otherwise the system immediately overrides the manual
     * value it was just given. Returns false (no write attempted) if
     * [hasPermission] is false; callers must check that first.
     */
    fun setBrightness(value: Int): Boolean {
        if (!hasPermission()) return false
        val resolver = context.contentResolver
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        return true
    }

    fun setPercent(percent: Int): Boolean =
        setBrightness(((percent.coerceIn(0, 100) / 100f) * 255).toInt())
}
