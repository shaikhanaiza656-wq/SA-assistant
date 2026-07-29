package com.sa.assistant.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.sa.assistant.data.model.PendingPdfImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a list of scanned/picked images into one real multi-page PDF via
 * [android.graphics.pdf.PdfDocument] — no placeholder pages, no stub
 * output. Each image is:
 *  1. decoded at a downsampled resolution (BitmapFactory.Options.inSampleSize)
 *     so a 12MP camera photo doesn't blow the heap,
 *  2. rotated to its correct upright orientation using the real EXIF tag
 *     (camera photos are very often stored sideways/upside-down with the
 *     correction only recorded in EXIF, not baked into the pixels),
 *  3. drawn scaled-to-fit and centered onto an A4 page at ~150dpi, which
 *     is the resolution real scanner apps target for a legible-but-not-huge
 *     scanned document.
 */
@Singleton
class ImageToPdfBuilder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // A4 at 150dpi: 8.27in x 11.69in
        private const val PAGE_WIDTH = 1240
        private const val PAGE_HEIGHT = 1754
        private const val MARGIN = 24f
        private const val MAX_DECODE_DIMENSION = 2000
    }

    class PdfBuildException(message: String) : IOException(message)

    /** Builds a PDF from [images] in the given order and writes it to
     *  [outputFile]. Throws [PdfBuildException] if not a single image
     *  could be decoded — this is never allowed to silently produce an
     *  empty/blank PDF and call it success. */
    suspend fun build(images: List<PendingPdfImage>, outputFile: File): Int = withContext(Dispatchers.IO) {
        if (images.isEmpty()) throw PdfBuildException("Koi image nahi mili PDF banane ke liye")

        val document = PdfDocument()
        var pagesWritten = 0

        for ((index, image) in images.withIndex()) {
            val bitmap = decodeUpright(image) ?: continue
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pagesWritten + 1).create()
                val page = document.startPage(pageInfo)
                drawFitted(bitmap, page.canvas)
                document.finishPage(page)
                pagesWritten += 1
            } finally {
                bitmap.recycle()
            }
        }

        if (pagesWritten == 0) {
            document.close()
            throw PdfBuildException("Koi bhi image decode nahi ho payi — file corrupt ho sakti hai")
        }

        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { document.writeTo(it) }
        document.close()
        pagesWritten
    }

    /** Draws [bitmap] centered on [canvas], scaled down (never up) to fit
     *  inside the page margins while preserving aspect ratio. */
    private fun drawFitted(bitmap: Bitmap, canvas: android.graphics.Canvas) {
        val maxW = PAGE_WIDTH - MARGIN * 2
        val maxH = PAGE_HEIGHT - MARGIN * 2
        val scale = minOf(maxW / bitmap.width, maxH / bitmap.height, 1f)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = (PAGE_WIDTH - drawW) / 2f
        val top = (PAGE_HEIGHT - drawH) / 2f
        val dest = RectF(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bitmap, null, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun decodeUpright(image: PendingPdfImage): Bitmap? {
        val orientation = readExifOrientation(image)
        val raw = decodeDownsampled(image) ?: return null
        return applyOrientation(raw, orientation)
    }

    private fun readExifOrientation(image: PendingPdfImage): Int {
        return try {
            val exif = image.sourceFile?.let { ExifInterface(it.absolutePath) }
                ?: openInputStream(image.uri)?.use { ExifInterface(it) }
            exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun decodeDownsampled(image: PendingPdfImage): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInputStream(image.uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }
            ?: return null

        var sampleSize = 1
        var w = boundsOptions.outWidth
        var h = boundsOptions.outHeight
        while (w / sampleSize > MAX_DECODE_DIMENSION || h / sampleSize > MAX_DECODE_DIMENSION) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return openInputStream(image.uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> return bitmap
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun openInputStream(uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            FileInputStream(File(requireNotNull(uri.path)))
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }
}
