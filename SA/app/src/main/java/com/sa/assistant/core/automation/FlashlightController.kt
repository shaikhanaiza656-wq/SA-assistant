package com.sa.assistant.core.automation

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real torch (flashlight) control via [CameraManager.setTorchMode] on
 * the rear camera's flash unit. No CAMERA permission is needed for
 * torch mode specifically — only for opening the camera for
 * preview/capture, which this never does.
 *
 * [isOn] is not a value SA remembers and hopes stays right — it is
 * driven live by [CameraManager.TorchCallback], the same callback the
 * system itself uses, so if the torch turns off for a reason outside
 * SA's control (another app opens the camera, the device gets too hot
 * and force-disables it) the state here updates to match reality
 * instead of drifting out of sync.
 */
@Singleton
class FlashlightController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val torchCameraId: String? by lazy { findTorchCapableCameraId() }

    private val _isOn = MutableStateFlow(false)
    val isOn: StateFlow<Boolean> = _isOn.asStateFlow()

    /** False on devices/emulators with no flash unit at all — the UI hides the tool rather than faking it. */
    val isAvailable: Boolean get() = torchCameraId != null

    init {
        torchCameraId?.let { id ->
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == id) _isOn.value = enabled
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == id) _isOn.value = false
                }
            }, null)
        }
    }

    private fun findTorchCapableCameraId(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        null
    }

    fun toggle(): Boolean = setOn(!_isOn.value)

    /** Returns false if there is no flash unit, or if the camera is currently held by another app. */
    fun setOn(on: Boolean): Boolean {
        val id = torchCameraId ?: return false
        return try {
            cameraManager.setTorchMode(id, on)
            true
        } catch (e: Exception) {
            false
        }
    }
}
