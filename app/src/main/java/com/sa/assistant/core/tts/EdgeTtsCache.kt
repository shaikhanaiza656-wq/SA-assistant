package com.sa.assistant.core.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local disk cache for Edge-TTS-generated speech, keyed by exactly what
 * changes the audio (text + voice + rate + pitch). Real file I/O against
 * the app's own cache dir — nothing fabricated. Same line spoken again with
 * the same settings is a cache hit and skips the network round-trip
 * entirely, cutting latency to near-zero and avoiding needless traffic.
 */
@Singleton
class EdgeTtsCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir: File by lazy {
        File(context.cacheDir, "edge_tts").apply { mkdirs() }
    }

    fun get(text: String, voice: String, rate: Float, pitch: Float): File? {
        val f = fileFor(text, voice, rate, pitch)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun put(text: String, voice: String, rate: Float, pitch: Float, audioBytes: ByteArray): File {
        val f = fileFor(text, voice, rate, pitch)
        f.writeBytes(audioBytes)
        return f
    }

    /** Best-effort trim so the cache doesn't grow unbounded on a long-running install. */
    fun trimIfNeeded(maxBytes: Long = DEFAULT_MAX_CACHE_BYTES) {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > maxBytes && i < files.size) {
            total -= files[i].length()
            runCatching { files[i].delete() }
            i++
        }
    }

    private fun fileFor(text: String, voice: String, rate: Float, pitch: Float): File {
        val key = "$voice|$rate|$pitch|$text"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.mp3")
    }

    companion object {
        private const val DEFAULT_MAX_CACHE_BYTES = 25L * 1024 * 1024 // 25 MB
    }
}
