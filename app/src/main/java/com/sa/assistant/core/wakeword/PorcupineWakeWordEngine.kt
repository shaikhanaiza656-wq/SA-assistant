package com.sa.assistant.core.wakeword

import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerErrorCallback
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real, always-on wake-word spotting for "SA" using Picovoice's Porcupine
 * on-device engine (`ai.picovoice:porcupine-android`, see app/build.gradle.kts).
 * Porcupine runs its own continuous native audio pipeline — there is no
 * SpeechRecognizer restart loop here at all, and CPU/battery cost is the
 * same low always-on-listening budget every Porcupine-based assistant uses.
 *
 * Honest requirement, not something this code can fake: detecting the exact
 * word "SA" needs a keyword model *trained specifically for "SA"*. Porcupine
 * does not ship "SA" as one of its built-in keywords (those are things like
 * "Hey Google", "Picovoice", "Bumblebee", etc.), and no library or model
 * file anyone hands you outside Picovoice's own console can honestly claim
 * to already be trained for "SA". Setup (one-time, free):
 *   1. Create a free account at https://console.picovoice.ai
 *   2. Console → Porcupine → train a custom wake word "SA" for Android
 *   3. Download the generated `SA_android_vX_Y_Z.ppn` file
 *   4. Copy it into app/src/main/assets/porcupine/ and set the exact
 *      filename in Settings (or [WakeWordPreferences.DEFAULT_KEYWORD_ASSET])
 *   5. Paste your Console AccessKey into Settings too
 *
 * Until both of those are present, [start] fails fast with
 * [PorcupineUnavailable] and [WakeWordListener] falls back to the existing
 * SpeechRecognizer-loop spotter instead of silently pretending Porcupine is
 * running.
 */
@Singleton
class PorcupineWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val micArbiter: MicArbiter
) {
    class PorcupineUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    private var manager: PorcupineManager? = null
    private var isPaused = false

    /**
     * Starts continuous spotting. [onWakeDetected] fires on Porcupine's own
     * callback thread every time "SA" is heard — callers must hop back to
     * the main thread themselves before touching UI/other mic resources.
     * Throws [PorcupineUnavailable] (caught by [WakeWordListener]) if the
     * AccessKey/keyword asset aren't configured yet, or if Porcupine itself
     * rejects them (bad key, keyword file doesn't match this app's
     * signature, etc.) — never silently no-ops.
     */
    @Throws(PorcupineUnavailable::class)
    fun start(accessKey: String, keywordAssetFileName: String, onWakeDetected: () -> Unit) {
        if (accessKey.isBlank()) {
            throw PorcupineUnavailable("No Picovoice AccessKey configured yet")
        }
        if (!micArbiter.acquire(MicArbiter.Owner.PORCUPINE)) {
            Log.d(TAG, "Mic busy with another owner — not starting Porcupine yet")
            return
        }
        val keywordFile = try {
            extractAssetToCache(keywordAssetFileName)
        } catch (e: Exception) {
            micArbiter.release(MicArbiter.Owner.PORCUPINE)
            throw PorcupineUnavailable("Keyword asset '$keywordAssetFileName' not found under assets/ — train it in Picovoice Console first", e)
        }

        try {
            val errorCallback = PorcupineManagerErrorCallback { error: PorcupineException ->
                Log.w(TAG, "Porcupine runtime error: ${error.message}")
            }
            val built = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf(keywordFile.absolutePath))
                .setSensitivities(floatArrayOf(DEFAULT_SENSITIVITY))
                .setErrorCallback(errorCallback)
                .build(context) { _ -> onWakeDetected() }
            built.start()
            manager = built
            isPaused = false
        } catch (e: PorcupineException) {
            micArbiter.release(MicArbiter.Owner.PORCUPINE)
            throw PorcupineUnavailable("Porcupine rejected the AccessKey/keyword file: ${e.message}", e)
        }
    }

    /** Full stop + resource release. Safe to call even if never started. */
    fun stop() {
        manager?.let {
            runCatching { it.stop() }
            runCatching { it.delete() }
        }
        manager = null
        isPaused = false
        micArbiter.release(MicArbiter.Owner.PORCUPINE)
    }

    /** Tears the mic down (without losing config) right before TTS speaks, or before
     *  the one-shot command-capture SpeechRecognizer session needs the mic instead. */
    fun pauseForOtherMicUse() {
        if (manager == null || isPaused) return
        isPaused = true
        runCatching { manager?.stop() }
        micArbiter.release(MicArbiter.Owner.PORCUPINE)
    }

    /** Re-opens the mic after [pauseForOtherMicUse], once it's free again. */
    fun resume() {
        val m = manager ?: return
        if (!isPaused) return
        if (!micArbiter.acquire(MicArbiter.Owner.PORCUPINE)) return
        runCatching { m.start() }
        isPaused = false
    }

    val isRunning: Boolean get() = manager != null

    private fun extractAssetToCache(assetPath: String): File {
        val outFile = File(context.cacheDir, "porcupine_" + assetPath.substringAfterLast('/'))
        if (outFile.exists() && outFile.length() > 0) return outFile
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    companion object {
        private const val TAG = "PorcupineWakeWordEngine"
        // 0.0 (fewer false accepts) .. 1.0 (fewer misses). 0.6 is Picovoice's own
        // documented reasonable default for a short custom keyword like "SA".
        private const val DEFAULT_SENSITIVITY = 0.6f
    }
}
