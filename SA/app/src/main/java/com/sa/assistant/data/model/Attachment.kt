package com.sa.assistant.data.model

/** Kind of file attached to an outgoing chat message. */
enum class AttachmentKind { IMAGE, FILE, PDF, CAMERA_PHOTO }

/**
 * A file attached to a message. [localPath] is an absolute path inside
 * this app's own storage (see [com.sa.assistant.core.files.AttachmentStorage]) —
 * content:// Uris from the system picker are copied there first because
 * the Termux Python process on the other end of the socket can't resolve
 * a content:// Uri, only a real filesystem path.
 */
data class Attachment(
    val localPath: String,
    val displayName: String,
    val mimeType: String,
    val kind: AttachmentKind
)
