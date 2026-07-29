package com.sa.assistant.data.model

/** UI-layer chat message, independent of the wire format so the Compose
 *  screens never need to know about [SaRequest]/[SaResponse] directly. */
data class ChatMessage(
    val id: Long,
    val text: String,
    val isFromUser: Boolean,
    val isPending: Boolean = false,
    val attachments: List<Attachment> = emptyList()
)
