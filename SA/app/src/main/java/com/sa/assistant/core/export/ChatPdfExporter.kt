package com.sa.assistant.core.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.sa.assistant.data.model.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders the current chat transcript into a real multi-page PDF using
 * [android.graphics.pdf.PdfDocument] — text wrapping, pagination, and
 * user/assistant styling are all handled here; nothing is hardcoded to a
 * single page or a fixed message count.
 */
@Singleton
class ChatPdfExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val pageWidth = 595 // A4 at 72dpi
    private val pageHeight = 842
    private val margin = 36f

    /** Renders [messages] to a PDF in app storage and returns a shareable
     *  content:// Uri (via FileProvider) pointing at it. */
    suspend fun export(messages: List<ChatMessage>): android.net.Uri = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        val userPaint = Paint().apply { textSize = 12f; isAntiAlias = true }
        val assistantPaint = Paint().apply { textSize = 12f; isAntiAlias = true; color = 0xFF3B82F6.toInt() }
        val labelPaint = Paint().apply { textSize = 9f; isAntiAlias = true; color = 0xFF9AA5B8.toInt() }
        val titlePaint = Paint().apply { textSize = 18f; isAntiAlias = true; isFakeBoldText = true }

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = margin

        canvas.drawText("SA Chat Export", margin, y, titlePaint)
        y += 20f
        canvas.drawText(
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date()),
            margin, y, labelPaint
        )
        y += 24f

        fun newPage() {
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = margin
        }

        for (message in messages) {
            if (message.isPending) continue // don't export an in-flight placeholder

            val who = if (message.isFromUser) "Aap" else "SA"
            val paint = if (message.isFromUser) userPaint else assistantPaint
            val maxWidth = pageWidth - margin * 2

            if (y > pageHeight - margin - 40f) newPage()
            canvas.drawText(who, margin, y, labelPaint)
            y += 14f

            val lines = wrapText(message.text, paint, maxWidth)
            for (line in lines) {
                if (y > pageHeight - margin) newPage()
                canvas.drawText(line, margin, y, paint)
                y += 16f
            }

            if (message.attachments.isNotEmpty()) {
                if (y > pageHeight - margin) newPage()
                val names = message.attachments.joinToString(", ") { it.displayName }
                canvas.drawText("Attachments: $names", margin, y, labelPaint)
                y += 16f
            }

            y += 10f
        }
        document.finishPage(page)

        val outDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val outFile = File(outDir, "SA_Chat_${System.currentTimeMillis()}.pdf")
        outFile.outputStream().use { document.writeTo(it) }
        document.close()

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    }

    /** Greedy word-wrap against the actual measured width of [paint] —
     *  no fixed character-count guess, so it's correct for any font size. */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
