package com.sa.assistant.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.sa.assistant.data.model.PdfAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bakes [PdfAnnotation] markup permanently into a real output PDF.
 * Reading goes through [PdfRenderer], writing through [PdfDocument] —
 * the same real-page-in, real-page-out approach as [PdfPageEditor], just
 * with an extra draw pass for each page's annotations before that page
 * is closed off. There is no separate "annotation layer" kept on top of
 * the original file — once written, marks are part of the page raster
 * itself, same as if the page had been marked with a highlighter pen
 * before being scanned.
 *
 * Kept as its own class (rather than folded into [PdfPageEditor]) so the
 * already-working merge/split/rotate/reorder flow in
 * [com.sa.assistant.ui.pdfpages.PdfPageManagerScreen] stays untouched.
 */
@Singleton
class PdfAnnotationEditor @Inject constructor() {
    class PdfAnnotateException(message: String) : IOException(message)

    companion object {
        // Matches PdfPageEditor's OUTPUT_RENDER_SCALE so mark/edit output
        // is exactly as sharp as merge/split/rotate output.
        private const val OUTPUT_RENDER_SCALE = 2
    }

    /** Real page count read straight off [file] on disk right now. */
    suspend fun pageCount(file: File): Int = withContext(Dispatchers.IO) {
        withRenderer(file) { it.pageCount }
    }

    /** Renders page [index] of [file] to a bitmap for the mark/edit canvas, longest side capped at [maxDimension]px. */
    suspend fun renderPage(file: File, index: Int, maxDimension: Int): Bitmap = withContext(Dispatchers.IO) {
        withRenderer(file) { renderer ->
            if (index !in 0 until renderer.pageCount) {
                throw PdfAnnotateException("Page $index file mein maujood nahi hai")
            }
            renderer.openPage(index).use { page ->
                val scale = maxDimension / maxOf(page.width, page.height).toFloat()
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    /**
     * Writes every real page of [sourceFile] to [outputFile], drawing
     * whichever annotations [annotationsByPage] has for that page (keyed
     * by zero-based page index) onto it before the page is closed off.
     * Pages with no entry in [annotationsByPage] are written unchanged.
     */
    suspend fun build(
        sourceFile: File,
        annotationsByPage: Map<Int, List<PdfAnnotation>>,
        outputFile: File
    ): Int = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) throw PdfAnnotateException("Source PDF nahi mili")

        val document = PdfDocument()
        var written = 0

        try {
            ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) {
                        throw PdfAnnotateException("PDF mein koi page nahi mila")
                    }
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { srcPage ->
                            val w = srcPage.width * OUTPUT_RENDER_SCALE
                            val h = srcPage.height * OUTPUT_RENDER_SCALE

                            val rendered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(rendered)
                            canvas.drawColor(Color.WHITE)
                            srcPage.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                            annotationsByPage[index]?.let { annotations ->
                                if (annotations.isNotEmpty()) {
                                    PdfAnnotationRenderer.drawOnBitmapCanvas(canvas, annotations, w, h)
                                }
                            }

                            val pageInfo = PdfDocument.PageInfo.Builder(w, h, written + 1).create()
                            val outPage = document.startPage(pageInfo)
                            outPage.canvas.drawBitmap(rendered, 0f, 0f, null)
                            document.finishPage(outPage)
                            rendered.recycle()
                            written += 1
                        }
                    }
                }
            }
        } catch (e: PdfAnnotateException) {
            document.close()
            throw e
        }

        if (written == 0) {
            document.close()
            throw PdfAnnotateException("Koi page likha nahi ja saka")
        }

        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { document.writeTo(it) }
        document.close()
        written
    }

    private fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T {
        if (!file.exists()) throw PdfAnnotateException("File maujood nahi hai: ${file.name}")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            return PdfRenderer(pfd).use(block)
        }
    }
}
