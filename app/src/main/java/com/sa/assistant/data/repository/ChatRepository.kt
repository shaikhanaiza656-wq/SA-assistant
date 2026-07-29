package com.sa.assistant.data.repository

import com.sa.assistant.socket.SaConnectionState
import com.sa.assistant.socket.SaSocketClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase-1 chat repository. Right now this is a thin pass-through over
 * [SaSocketClient]: it exposes connection state and the raw response
 * stream so the ViewModel layer never touches sockets directly. Later
 * phases add local persistence (Room) here for chat history without the
 * ViewModel or UI needing to change.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val socketClient: SaSocketClient
) {
    val connectionState: StateFlow<SaConnectionState> = socketClient.connectionState
    val responses: SharedFlow<com.sa.assistant.data.model.SaResponse> = socketClient.incoming

    fun connect() = socketClient.start()

    fun disconnect() = socketClient.stop()

    suspend fun sendMessage(
        text: String,
        attachments: List<com.sa.assistant.data.model.SaAttachmentPayload> = emptyList()
    ): Long = socketClient.send(text, type = "chat", attachments = attachments)
}
