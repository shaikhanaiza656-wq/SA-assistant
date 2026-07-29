package com.sa.assistant.data.model

import kotlinx.serialization.Serializable

/**
 * A single file reference sent alongside a chat request. Only a
 * filesystem path is sent — never raw file bytes — since attachments can
 * be tens of MB and this is a plain JSON-over-TCP link, not a chunked
 * upload protocol. [AttachmentStorage][com.sa.assistant.core.files.AttachmentStorage]
 * guarantees [path] is already a real absolute path the Termux process
 * (same device, shared filesystem) can open directly.
 */
@Serializable
data class SaAttachmentPayload(
    val path: String,
    val mimeType: String
)

/**
 * Outgoing message: App -> Termux Python server.
 *
 * Matches the wire format from the project spec:
 * { "type": "command", "text": "mera naam kya hai", "id": 1 }
 *
 * [attachments] is an additive Phase-2 field with a default, so a Phase-1
 * Python server that only reads type/text/id keeps working unmodified.
 */
@Serializable
data class SaRequest(
    val type: String,
    val text: String,
    val id: Long,
    val attachments: List<SaAttachmentPayload> = emptyList()
)

/**
 * Incoming message: Termux Python server -> App.
 *
 * Matches: { "status": "success", "reply": "Aapka naam Shahnawaz hai.", "id": 1 }
 *
 * [done] is an additive Phase-2 field enabling streaming: the server may
 * send several lines with the same [id] and [done]=false, each carrying
 * the next chunk of [reply], followed by one final line with [done]=true.
 * A Phase-1 server that only ever sends one complete reply per id doesn't
 * need to set this field at all — it defaults to true, so single-shot
 * replies keep working exactly as before.
 */
@Serializable
data class SaResponse(
    val status: String,
    val reply: String,
    val id: Long,
    val done: Boolean = true
)

/** Request "type" values the Python router understands. Kept as an enum
 *  on the Kotlin side so call sites can't typo a raw string; serialized
 *  to/from its [wireValue] for the actual JSON payload. */
enum class SaRequestType(val wireValue: String) {
    COMMAND("command"),
    CHAT("chat"),
    PING("ping");

    companion object {
        fun fromWire(value: String): SaRequestType =
            entries.firstOrNull { it.wireValue == value } ?: COMMAND
    }
}
