package com.sa.assistant.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One page pulled from an existing on-disk PDF: which file, which
 * zero-based page index inside it, and how much extra rotation to bake
 * in on top of the page's own stored orientation when it's written into
 * an output document.
 *
 * Merge, split, rotate and reorder are all really just "write this list
 * of real source pages, in this order, with this rotation" under the
 * hood — so [PdfPageEditor.build] backs all four honestly with one real
 * implementation instead of four separate half-features.
 */
data class SourcePageRef(
    val sourceFile: File,
    val pageIndex: Int,
    val rotationDegrees: Int = 0
)

/**
 * Reads and rewrites real PDF pages — no placeholder pages, no blank
 * stand-ins, nothing faked. Reading goes through
 * [android.graphics.pdf.PdfRenderer] (page count, preview bitmaps);
 * writing goes through [android.graphics.pdf.PdfDocument], re-rendering
 * every source page at higher fidelity than a thumbnail so merge/split/
 * rotate output stays legible without needing a third-party PDF library.
 */
@Singleton
class PdfPageEditor @Inject constructor() {
    class PdfEditException(message: String) : IOException(message)

    companion object {
        // Source pages are rendered at 2x their native point-size before
        // being written into the output document, so a plain (non-scan)
        // PDF doesn't come out blurrier than it needs to after the
        // raster round-trip that merge/split/rotate require here.
        private const val OUTPUT_RENDER_SCALE = 2
    }

    /** Real page count read straight off [file] on disk right now. */
    suspend fun pageCount(file: File): Int = withContext(Dispatchers.IO) {
        withRenderer(file) { it.pageCount }
    }

    /** Renders page [index] of [file] to a bitmap for preview, longest side capped at [maxDimension]px. */
    suspend fun renderPage(file: File, index: Int, maxDimension: Int): Bitmap = withContext(Dispatchers.IO) {
        withRenderer(file) { renderer ->
            if (index !in 0 until renderer.pageCount) {
                throw PdfEditException("Page $index file mein maujood nahi hai")
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
     * Builds a new PDF at [outputFile] from [pages] in the given order.
     * Every page is opened from its real source file and re-rendered —
     * this throws [PdfEditException] rather than silently writing an
     * empty file if not one single page could be read.
     */
    suspend fun build(pages: List<SourcePageRef>, outputFile: File): Int = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) throw PdfEditException("Output mein kam se kam ek page hona chahiye")

        val renderers = mutableMapOf<String, PdfRenderer>()
        val pfds = mutableMapOf<String, ParcelFileDescriptor>()
        val document = PdfDocument()
        var written = 0

        try {
            for (ref in pages) {
                if (!ref.sourceFile.exists()) continue
                val key = ref.sourceFile.absolutePath
                val renderer = renderers.getOrPut(key) {
                    val pfd = ParcelFileDescriptor.open(ref.sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    pfds[key] = pfd
                    PdfRenderer(pfd)
                }
                if (ref.pageIndex !in 0 until renderer.pageCount) continue

                renderer.openPage(ref.pageIndex).use { srcPage ->
                    val rotation = ((ref.rotationDegrees % 360) + 360) % 360
                    val swap = rotation == 90 || rotation == 270
                    val rawW = srcPage.width * OUTPUT_RENDER_SCALE
                    val rawH = srcPage.height * OUTPUT_RENDER_SCALE
                    val outW = if (swap) rawH else rawW
                    val outH = if (swap) rawW else rawH

                    val rendered = Bitmap.createBitmap(rawW, rawH, Bitmap.Config.ARGB_8888)
                    Canvas(rendered).drawColor(Color.WHITE)
                    srcPage.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val pageInfo = PdfDocument.PageInfo.Builder(outW, outH, written + 1).create()
                    val outPage = document.startPage(pageInfo)
                    outPage.canvas.drawColor(Color.WHITE)
                    if (rotation != 0) {
                        outPage.canvas.save()
                        outPage.canvas.translate(outW / 2f, outH / 2f)
                        outPage.canvas.rotate(rotation.toFloat())
                        outPage.canvas.translate(-rawW / 2f, -rawH / 2f)
                        outPage.canvas.drawBitmap(rendered, 0f, 0f, null)
                        outPage.canvas.restore()
                    } else {
                        outPage.canvas.drawBitmap(rendered, 0f, 0f, null)
                    }
                    document.finishPage(outPage)
                    rendered.recycle()
                    written += 1
                }
            }
        } finally {
            renderers.values.forEach { it.close() }
            pfds.values.forEach { it.close() }
        }

        if (written == 0) {
            document.close()
            throw PdfEditException("Koi valid page likha nahi ja saka")
        }

        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { document.writeTo(it) }
        document.close()
        written
    }

    private fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T {
        if (!file.exists()) throw PdfEditException("File maujood nahi hai: ${file.name}")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            return PdfRenderer(pfd).use(block)
        }
    }
}
