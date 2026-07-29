package com.sa.assistant.data.model

/**
 * Phase 3 Part 2B: PDF Mark/Edit.
 *
 * [android.graphics.pdf.PdfRenderer] does not expose a PDF's underlying
 * text layer (no word/line boxes to select), so "highlight", "underline"
 * and "strikethrough" here are real, honest gesture-based markup: the
 * user drags across the area they want marked and a highlighter
 * rectangle / underline / strikethrough line is placed there — the same
 * way someone would mark a printed page with a highlighter pen. This is
 * not OCR-based text selection, and nothing here pretends it is.
 *
 * Every annotation stores its geometry in *normalized* page space — x/y
 * as a 0f..1f fraction of the page's own width/height. That means the
 * exact same annotation list renders correctly whether it's drawn on a
 * small on-screen preview bitmap or baked into the full-resolution
 * output page — nothing needs to know a page's raw pixel size to place
 * a mark correctly on it.
 */

enum class AnnotationTool {
    NONE,
    HIGHLIGHT,
    UNDERLINE,
    STRIKETHROUGH,
    FREEHAND,
    TEXT,
    STICKY_NOTE,
    SHAPE
}

/** Which outline [PdfAnnotation.Shape] draws — chosen separately from [AnnotationTool.SHAPE]. */
enum class ShapeType {
    RECTANGLE,
    OVAL,
    LINE,
    ARROW
}

/** A single point in normalized (0f..1f) page space. */
data class NormPoint(val x: Float, val y: Float)

sealed class PdfAnnotation {
    abstract val id: String
    abstract val pageIndex: Int
    abstract val colorArgb: Int

    /** A translucent highlighter-colored rectangle over a region the user dragged across. */
    data class Highlight(
        override val id: String,
        override val pageIndex: Int,
        override val colorArgb: Int,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val alpha: Int = 90
    ) : PdfAnnotation()

    /** A line placed under the area the user dragged across. */
    data class Underline(
        override val id: String,
        override val pageIndex: Int,
        override val colorArgb: Int,
        val startX: Float,
        val endX: Float,
        val y: Float,
        val strokeWidthFraction: Float = 0.004f
    ) : PdfAnnotation()

    /** A line placed through the middle of the area the user dragged across. */
    data class Strikethrough(
        override val id: String,
        override val pageIndex: Int,
        override val colorArgb: Int,
        val startX: Float,
        val endX: Float,
        val y: Float,
        val strokeWidthFraction: Float = 0.004f
    ) : PdfAnnotation()

    /** A free-hand drawn stroke — every point the user's finger actually passed through while dragging. */
    data class Freehand(
        override val id: String,
        override val pageIndex: Int,
        override val colorArgb: Int,
        val points: List<NormPoint>,
        val strokeWidthFraction: Float = 0.006f
    ) : PdfAnnotation()

    /**
     * A real piece of text drawn straight onto the page at the tapped
     * spot — same "baked into the raster" rule as every other tool here,
     * so there is no separate interactive text layer to fake.
     */
    data class TextBox(
        override val id: String,
        override val pageIndex: Int,
        override val colorArgb: Int,
        val x: Float,
        val y: Float,
        val text: String,
        val fontSizeFraction: Float = 0.024f
    ) : PdfAnnotation()

    /**
     * A visible post-it-style note baked onto the page at the tapped
     * spot. [android.graphics.pdf.PdfDocument] gives no interactive
     * popup-annotation API to hook into (the whole engine only ever
     * writes flat page rasters — see [com.sa.assistant.core.pdf.PdfAnnotationEditor]),
     * so this is an honest always-visible colored note box with the
     * text written on it, not a collapsible pretend-native annotation.
     */
    data class StickyNote(
        override val id: String,
        override val pageIndex: Int,
        override val colorArgb: Int,
        val x: Float,
        val y: Float,
        val text: String,
        val widthFraction: Float = 0.22f
    ) : PdfAnnotation()

    /** An outline shape dragged from one corner/end to the other. */
    data class Shape(
        override val id: String,
        override val pageIndex: Int,
        override val colorArgb: Int,
        val shapeType: ShapeType,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val strokeWidthFraction: Float = 0.006f
    ) : PdfAnnotation()
}
