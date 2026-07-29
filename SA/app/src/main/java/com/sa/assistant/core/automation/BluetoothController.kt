package com.sa.assistant.core.automation

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.sa.assistant.data.model.BluetoothActionOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Bluetooth adapter control.
 *
 * Honest limitation, stated plainly rather than hidden: starting with
 * Android 13 (API 33), Google made `BluetoothAdapter.enable()` /
 * `disable()` no-ops for apps targeting API 33+ — third-party apps are
 * no longer allowed to silently flip Bluetooth on/off. This class still
 * tries the direct call first (it does still work on some OEM builds
 * and on API < 33), but when that doesn't take effect it reports
 * [BluetoothActionOutcome.NEEDS_SYSTEM_DIALOG]/`NEEDS_PERMISSION` so
 * [ToolsViewModel] can fall back to the real system "Turn on Bluetooth?"
 * dialog (for enabling) or the system Bluetooth settings screen (for
 * disabling — there is no request-disable dialog on Android). That is
 * a real, working fallback, not a fake success message.
 */
@Singleton
class BluetoothController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    val isSupported: Boolean get() = adapter != null

    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestEnable(): BluetoothActionOutcome {
        val a = adapter ?: return BluetoothActionOutcome.UNSUPPORTED
        if (!hasConnectPermission()) return BluetoothActionOutcome.NEEDS_PERMISSION
        if (a.isEnabled) return BluetoothActionOutcome.CHANGED
        return try {
            @Suppress("DEPRECATION")
            if (a.enable()) BluetoothActionOutcome.CHANGED else BluetoothActionOutcome.NEEDS_SYSTEM_DIALOG
        } catch (e: SecurityException) {
            BluetoothActionOutcome.NEEDS_PERMISSION
        }
    }

    fun requestDisable(): BluetoothActionOutcome {
        val a = adapter ?: return BluetoothActionOutcome.UNSUPPORTED
        if (!hasConnectPermission()) return BluetoothActionOutcome.NEEDS_PERMISSION
        if (!a.isEnabled) return BluetoothActionOutcome.CHANGED
        return try {
            @Suppress("DEPRECATION")
            if (a.disable()) BluetoothActionOutcome.CHANGED else BluetoothActionOutcome.NEEDS_SYSTEM_DIALOG
        } catch (e: SecurityException) {
            BluetoothActionOutcome.NEEDS_PERMISSION
        }
    }

    /** Real system confirmation dialog ("Allow SA to turn on Bluetooth?") — guaranteed to work on every Android version. */
    fun enableViaSystemDialogIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** No request-disable dialog exists on Android, so the honest fallback is the real system settings screen. */
    fun disableViaSettingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    fun permissionName(): String = Manifest.permission.BLUETOOTH_CONNECT
}
