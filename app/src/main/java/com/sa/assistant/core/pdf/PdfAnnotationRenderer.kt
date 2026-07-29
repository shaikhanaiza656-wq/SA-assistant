package com.sa.assistant.core.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.sa.assistant.data.model.PdfAnnotation
import com.sa.assistant.data.model.ShapeType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a [PdfAnnotation] list (normalized 0f..1f page coordinates) into
 * real draw calls on a real [Canvas] of a known pixel size. This is used
 * by [PdfAnnotationEditor] when baking the final output page, so the
 * exact same math that draws the live on-screen preview in
 * [com.sa.assistant.ui.pdfmark.PdfMarkScreen] is what ends up in the
 * saved PDF.
 */
object PdfAnnotationRenderer {

    fun drawOnBitmapCanvas(canvas: Canvas, annotations: List<PdfAnnotation>, widthPx: Int, heightPx: Int) {
        for (annotation in annotations) {
            drawSingle(canvas, annotation, widthPx, heightPx)
        }
    }

    /**
     * Draws exactly one annotation. Pulled out of [drawOnBitmapCanvas] so
     * [com.sa.assistant.ui.pdfmark.PdfMarkScreen]'s live on-screen preview
     * can call the very same drawing code (via `nativeCanvas`) that later
     * bakes the final saved PDF — for Text/Sticky-Note/Shape there is
     * exactly one implementation, not a preview version and a separate
     * "real" version that could silently drift apart.
     */
    fun drawSingle(canvas: Canvas, annotation: PdfAnnotation, widthPx: Int, heightPx: Int) {
        when (annotation) {
            is PdfAnnotation.Highlight -> drawHighlight(canvas, annotation, widthPx, heightPx)
            is PdfAnnotation.Underline -> drawUnderline(canvas, annotation, widthPx, heightPx)
            is PdfAnnotation.Strikethrough -> drawStrikethrough(canvas, annotation, widthPx, heightPx)
            is PdfAnnotation.Freehand -> drawFreehand(canvas, annotation, widthPx, heightPx)
            is PdfAnnotation.TextBox -> drawTextBox(canvas, annotation, widthPx, heightPx)
            is PdfAnnotation.StickyNote -> drawStickyNote(canvas, annotation, widthPx, heightPx)
            is PdfAnnotation.Shape -> drawShape(canvas, annotation, widthPx, heightPx)
        }
    }

    private fun drawHighlight(canvas: Canvas, a: PdfAnnotation.Highlight, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a.colorArgb
            alpha = a.alpha
            style = Paint.Style.FILL
        }
        canvas.drawRect(a.minX * w, a.minY * h, a.maxX * w, a.maxY * h, paint)
    }

    private fun drawUnderline(canvas: Canvas, a: PdfAnnotation.Underline, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a.colorArgb
            style = Paint.Style.STROKE
            strokeWidth = a.strokeWidthFraction * w
            strokeCap = Paint.Cap.ROUND
        }
        val y = a.y * h
        canvas.drawLine(a.startX * w, y, a.endX * w, y, paint)
    }

    private fun drawStrikethrough(canvas: Canvas, a: PdfAnnotation.Strikethrough, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a.colorArgb
            style = Paint.Style.STROKE
            strokeWidth = a.strokeWidthFraction * w
            strokeCap = Paint.Cap.ROUND
        }
        val y = a.y * h
        canvas.drawLine(a.startX * w, y, a.endX * w, y, paint)
    }

    private fun drawFreehand(canvas: Canvas, a: PdfAnnotation.Freehand, w: Int, h: Int) {
        if (a.points.size < 2) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a.colorArgb
            style = Paint.Style.STROKE
            strokeWidth = a.strokeWidthFraction * w
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        path.moveTo(a.points[0].x * w, a.points[0].y * h)
        for (i in 1 until a.points.size) {
            path.lineTo(a.points[i].x * w, a.points[i].y * h)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawTextBox(canvas: Canvas, a: PdfAnnotation.TextBox, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a.colorArgb
            textSize = a.fontSizeFraction * w
        }
        // Baseline sits fontSize below the tapped point, same as a text
        // cursor placed at that spot — matches what the user tapped on.
        canvas.drawText(a.text, a.x * w, a.y * h + paint.textSize, paint)
    }

    private fun drawStickyNote(canvas: Canvas, a: PdfAnnotation.StickyNote, w: Int, h: Int) {
        val notePaddingPx = 0.012f * w
        val fontSizePx = 0.018f * w
        val noteWidthPx = a.widthFraction * w

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            color = if (isDark(a.colorArgb)) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        }
        val maxTextWidthPx = noteWidthPx - 2 * notePaddingPx
        val lines = wrapText(a.text.ifBlank { " " }, textPaint, maxTextWidthPx)
        val lineHeightPx = fontSizePx * 1.25f
        val noteHeightPx = 2 * notePaddingPx + lines.size * lineHeightPx

        val left = a.x * w
        val top = a.y * h
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a.colorArgb
            style = Paint.Style.FILL
        }
        val rect = RectF(left, top, left + noteWidthPx, top + noteHeightPx)
        canvas.drawRoundRect(rect, notePaddingPx, notePaddingPx, notePaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(70, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 0.0015f * w
        }
        canvas.drawRoundRect(rect, notePaddingPx, notePaddingPx, borderPaint)

        var baselineY = top + notePaddingPx + fontSizePx
        for (line in lines) {
            canvas.drawText(line, left + notePaddingPx, baselineY, textPaint)
            baselineY += lineHeightPx
        }
    }

    private fun drawShape(canvas: Canvas, a: PdfAnnotation.Shape, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a.colorArgb
            style = Paint.Style.STROKE
            strokeWidth = a.strokeWidthFraction * w
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val startX = a.startX * w
        val startY = a.startY * h
        val endX = a.endX * w
        val endY = a.endY * h

        when (a.shapeType) {
            ShapeType.RECTANGLE -> canvas.drawRect(
                minOf(startX, endX), minOf(startY, endY), maxOf(startX, endX), maxOf(startY, endY), paint
            )
            ShapeType.OVAL -> canvas.drawOval(
                RectF(minOf(startX, endX), minOf(startY, endY), maxOf(startX, endX), maxOf(startY, endY)), paint
            )
            ShapeType.LINE -> canvas.drawLine(startX, startY, endX, endY, paint)
            ShapeType.ARROW -> {
                canvas.drawLine(startX, startY, endX, endY, paint)
                val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
                val headLength = maxOf(0.02f * w, paint.strokeWidth * 4f)
                val headAngle = Math.toRadians(28.0)
                val leftX = endX - headLength * cos(angle - headAngle).toFloat()
                val leftY = endY - headLength * sin(angle - headAngle).toFloat()
                val rightX = endX - headLength * cos(angle + headAngle).toFloat()
                val rightY = endY - headLength * sin(angle + headAngle).toFloat()
                canvas.drawLine(endX, endY, leftX, leftY, paint)
                canvas.drawLine(endX, endY, rightX, rightY, paint)
            }
        }
    }

    private fun isDark(colorArgb: Int): Boolean {
        val r = android.graphics.Color.red(colorArgb)
        val g = android.graphics.Color.green(colorArgb)
        val b = android.graphics.Color.blue(colorArgb)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return luminance < 0.55
    }

    /** Greedy word-wrap using the paint's own text metrics, so wrapped lines always fit [maxWidthPx]. */
    private fun wrapText(text: String, paint: Paint, maxWidthPx: Float): List<String> {
        if (maxWidthPx <= 0f) return listOf(text)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidthPx || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
