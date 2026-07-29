package com.sa.assistant.core.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.sa.assistant.data.model.PdfEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the app-private "pdfs/" folder that PDF Studio saves into. Page
 * counts come from actually opening each file with
 * [android.graphics.pdf.PdfRenderer] — a real page count read off the
 * real file, not something stashed in a database that could drift from
 * what's on disk.
 */
@Singleton
class PdfStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val pdfDir: File by lazy {
        File(context.getExternalFilesDir(null), "pdfs").apply { mkdirs() }
    }

    private val scanCaptureDir: File by lazy {
        File(context.getExternalFilesDir(null), "pdf_scans").apply { mkdirs() }
    }

    /** Fresh destination file for a camera scan capture, inside the
     *  FileProvider-exposed pdf_scans/ directory (kept separate from the
     *  chat feature's attachments/ so PDF Studio's scan tray doesn't get
     *  tangled up with chat attachments). */
    fun newScanCaptureFile(): File {
        val name = "scan_${System.currentTimeMillis()}.jpg"
        return File(scanCaptureDir, name)
    }

    /** Reserves a fresh output file for a PDF being built now.
     *  [requestedName] is sanitized to a safe filename; if blank, or if it
     *  collides with an existing file, a timestamp-based name is used. */
    fun newOutputFile(requestedName: String?): File {
        val cleaned = requestedName
            ?.trim()
            ?.ifBlank { null }
            ?.replace(Regex("[^A-Za-z0-9 _-]"), "")
            ?.replace(Regex("\\s+"), "_")

        val base = cleaned?.takeIf { it.isNotBlank() } ?: "SA_Scan_${System.currentTimeMillis()}"
        var candidate = File(pdfDir, "$base.pdf")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(pdfDir, "${base}_$suffix.pdf")
            suffix += 1
        }
        return candidate
    }

    suspend fun listSaved(): List<PdfEntry> = withContext(Dispatchers.IO) {
        val files = pdfDir.listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }
            ?: emptyArray()

        files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                PdfEntry(
                    file = file,
                    displayName = file.nameWithoutExtension,
                    pageCount = readPageCount(file),
                    sizeBytes = file.length(),
                    createdAtMillis = file.lastModified()
                )
            }
    }

    suspend fun delete(entry: PdfEntry): Boolean = withContext(Dispatchers.IO) {
        entry.file.delete()
    }

    fun shareUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun readPageCount(file: File): Int {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            }
        } catch (e: IOException) {
            0
        }
    }
}
