package com.sa.assistant.socket

import android.util.Log
import com.sa.assistant.data.model.SaRequest
import com.sa.assistant.data.model.SaResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class SaConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Persistent TCP client for the App <-> Termux link described in the spec.
 *
 * Framing: newline-delimited JSON. Each [SaRequest] is serialized to one
 * line and written with a trailing '\n'; the Python side is expected to
 * reply the same way. This is the simplest framing that a stock Python
 * `socket` + `readline()` loop can produce without extra dependencies.
 *
 * Reconnection: if the connection drops, [start] retries with a fixed
 * backoff rather than giving up, since the Termux server may simply not
 * be running yet (user hasn't opened Termux) or may restart.
 */
@Singleton
class SaSocketClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var connectionJob: Job? = null
    private val requestId = AtomicLong(0)

    private val _connectionState = MutableStateFlow(SaConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SaConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<SaResponse>(extraBufferCapacity = 32)
    val incoming: SharedFlow<SaResponse> = _incoming.asSharedFlow()

    /** Starts a background connect-and-listen loop against [host]:[port]. Safe to call
     *  repeatedly; a second call is a no-op while already connecting/connected. */
    fun start(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT) {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            while (isActive) {
                try {
                    connectOnce(host, port)
                } catch (e: IOException) {
                    Log.w(TAG, "Socket connection failed: ${e.message}")
                }
                _connectionState.value = SaConnectionState.DISCONNECTED
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    fun stop() {
        connectionJob?.cancel()
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        outputStream = null
        _connectionState.value = SaConnectionState.DISCONNECTED
    }

    /** Sends [text] as a [type] command, optionally with file [attachments],
     *  and returns the auto-assigned request id. */
    suspend fun send(
        text: String,
        type: String = "command",
        attachments: List<com.sa.assistant.data.model.SaAttachmentPayload> = emptyList()
    ): Long {
        val id = requestId.incrementAndGet()
        val request = SaRequest(type = type, text = text, id = id, attachments = attachments)
        val line = json.encodeToString(SaRequest.serializer(), request) + "\n"
        writeLock.withLock {
            val stream = outputStream ?: throw IOException("Not connected to SA server")
            withContext(Dispatchers.IO) {
                stream.write(line.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
        return id
    }

    private suspend fun connectOnce(host: String, port: Int) = withContext(Dispatchers.IO) {
        _connectionState.value = SaConnectionState.CONNECTING
        val newSocket = Socket().apply {
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tcpNoDelay = true
        }
        socket = newSocket
        outputStream = newSocket.getOutputStream()
        _connectionState.value = SaConnectionState.CONNECTED
        Log.i(TAG, "Connected to SA server at $host:$port")

        val reader = BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8))
        try {
            while (isActive) {
                val line = reader.readLine() ?: break // null = server closed the connection
                if (line.isBlank()) continue
                runCatching { json.decodeFromString(SaResponse.serializer(), line) }
                    .onSuccess { _incoming.emit(it) }
                    .onFailure { Log.w(TAG, "Malformed response, ignoring: $line") }
            }
        } finally {
            runCatching { newSocket.close() }
        }
    }

    companion object {
        private const val TAG = "SaSocketClient"
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8765
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val RECONNECT_DELAY_MS = 3000L
    }
}
