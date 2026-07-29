package com.sa.assistant.data.model

import android.net.Uri
import java.io.File

/**
 * A single image queued up in PDF Studio, waiting to become a page.
 * [uri] is what's actually rendered from — a content:// Uri for a
 * gallery pick, or a file:// wrapped Uri for a fresh camera capture.
 * [sourceFile] is kept only for camera captures so the builder can read
 * EXIF orientation straight off disk instead of through a content
 * resolver stream (more reliable, and camera files are already ours).
 */
data class PendingPdfImage(
    val id: String,
    val uri: Uri,
    val sourceFile: File?,
    val label: String
)

/** A PDF already saved to disk under PDF Studio's own storage folder. */
data class PdfEntry(
    val file: File,
    val displayName: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val createdAtMillis: Long
)

/**
 * One page currently loaded into the Page Manager screen (Phase 3 Part
 * 2A — merge/split/rotate/reorder/delete). [key] is a stable synthetic
 * id used for Compose keys and selection, since the same real page (same
 * [sourceFile] + [pageIndex]) can end up in the list twice after a
 * merge. Every entry always points at a real page inside a real file on
 * disk — there is no synthetic/blank page type.
 */
data class ManagedPage(
    val key: String,
    val sourceFile: File,
    val pageIndex: Int,
    val rotationDegrees: Int = 0
)
