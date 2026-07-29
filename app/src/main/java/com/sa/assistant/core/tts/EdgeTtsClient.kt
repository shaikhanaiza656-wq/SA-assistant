package com.sa.assistant.core.tts

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real client for Microsoft Edge's "Read Aloud" neural TTS service — the
 * same free endpoint the Edge browser itself uses, and the same protocol
 * the widely-used open-source `edge-tts` project implements. There is no
 * official public SDK for this (Microsoft doesn't publish one), so this is
 * a from-scratch Kotlin implementation of that documented WebSocket
 * protocol over real OkHttp sockets — not a mocked/fake network call.
 *
 * Because this talks to an undocumented, reverse-engineered endpoint,
 * Microsoft can change or rate-limit it at any time without notice. That's
 * exactly why [SaTextToSpeech] treats any failure here as a signal to fall
 * back to Android's own [android.speech.tts.TextToSpeech] (requirement:
 * "if Edge TTS is unavailable, fall back to Android TextToSpeech") rather
 * than surfacing an error to the user.
 */
@Singleton
class EdgeTtsClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Synthesizes [text] with [voiceName] (an Edge neural voice id like
     * "hi-IN-SwaraNeural") and returns raw MP3 bytes, or null if the
     * service couldn't be reached / responded unexpectedly. [ratePercent]
     * and [pitchPercent] follow Edge's own SSML `rate`/`pitch` percentage
     * convention (e.g. "+0%", "-10%").
     */
    fun synthesize(
        text: String,
        voiceName: String,
        ratePercent: String = "+0%",
        pitchPercent: String = "+0Hz"
    ): ByteArray? {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val url = "$ENDPOINT?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&ConnectionId=$connectionId"

        val audioBuffer = ByteArrayOutputStream()
        val doneLatch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val request = Request.Builder()
            .url(url)
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSpeechConfigMessage())
                webSocket.send(buildSsmlMessage(requestId, text, voiceName, ratePercent, pitchPercent))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    doneLatch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                extractAudioChunk(bytes)?.let { audioBuffer.write(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set(t)
                doneLatch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                doneLatch.countDown()
            }
        }

        val webSocket = client.newWebSocket(request, listener)
        val finished = doneLatch.await(SYNTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { webSocket.cancel() }
            Log.w(TAG, "Edge TTS timed out waiting for turn.end")
            return null
        }
        failure.get()?.let {
            Log.w(TAG, "Edge TTS socket failed: ${it.message}")
            return null
        }
        val bytes = audioBuffer.toByteArray()
        return bytes.ifEmpty { null }
    }

    /**
     * Binary frames from the service are a text header followed by raw
     * audio, separated by the byte sequence "Path:audio\r\n\r\n". This
     * strips that header and returns just the audio payload.
     */
    private fun extractAudioChunk(bytes: ByteString): ByteArray? {
        val raw = bytes.toByteArray()
        val headerMarker = "Path:audio\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val idx = indexOf(raw, headerMarker)
        if (idx < 0) return null
        val start = idx + headerMarker.size
        if (start >= raw.size) return null
        return raw.copyOfRange(start, raw.size)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun buildSpeechConfigMessage(): String {
        val timestamp = xTimestamp()
        return "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":false,\"wordBoundaryEnabled\":false}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
    }

    private fun buildSsmlMessage(
        requestId: String,
        text: String,
        voiceName: String,
        ratePercent: String,
        pitchPercent: String
    ): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voiceName'>" +
            "<prosody rate='$ratePercent' pitch='$pitchPercent'>$escaped</prosody>" +
            "</voice></speak>"
        val timestamp = xTimestamp()
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:$timestamp\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    private fun xTimestamp(): String =
        java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    companion object {
        private const val TAG = "EdgeTtsClient"
        private const val ENDPOINT = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        // Public token used by Microsoft Edge's own Read-Aloud feature — the
        // same constant every open-source edge-tts client uses; not a secret
        // credential of ours.
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val NORMAL_CLOSURE = 1000
        private const val SYNTH_TIMEOUT_SECONDS = 12L
    }
}
