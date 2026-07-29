package com.sa.assistant.core.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sa.assistant.data.model.Attachment
import com.sa.assistant.data.model.AttachmentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a content:// Uri (from a system picker, camera capture, etc.)
 * into this app's own external-files "attachments" folder and returns a
 * real filesystem [Attachment]. This is not decorative — it's required
 * because content:// Uris are only resolvable through Android's
 * ContentResolver inside this process; the Termux Python process on the
 * other end of the socket needs an actual path it can `open()`.
 */
@Singleton
class AttachmentStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val attachmentsDir: File by lazy {
        File(context.getExternalFilesDir(null), "attachments").apply { mkdirs() }
    }

    suspend fun importFromUri(uri: Uri, kind: AttachmentKind): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: guessMimeType(kind)
        val originalName = queryDisplayName(uri) ?: "attachment_${UUID.randomUUID()}"
        val safeName = "${System.currentTimeMillis()}_${originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        val destFile = File(attachmentsDir, safeName)

        resolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("Could not open input stream for $uri")

        Attachment(
            localPath = destFile.absolutePath,
            displayName = originalName,
            mimeType = mimeType,
            kind = kind
        )
    }

    /** For camera capture: the file already exists on disk (written directly
     *  by the camera app via FileProvider), so no copy is needed — just
     *  wrap it as an [Attachment]. */
    fun fromCapturedFile(file: File): Attachment = Attachment(
        localPath = file.absolutePath,
        displayName = file.name,
        mimeType = "image/jpeg",
        kind = AttachmentKind.CAMERA_PHOTO
    )

    /** Creates a fresh destination file+Uri pair for a camera capture,
     *  inside the same FileProvider-exposed directory as [importFromUri]. */
    fun newCaptureFile(): File {
        val name = "capture_${System.currentTimeMillis()}.jpg"
        return File(attachmentsDir, name)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }

    private fun guessMimeType(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.IMAGE, AttachmentKind.CAMERA_PHOTO -> "image/jpeg"
        AttachmentKind.PDF -> "application/pdf"
        AttachmentKind.FILE -> "application/octet-stream"
    }
}
