package com.sa.assistant.ui.pdfstudio

import android.net.Uri

sealed class PdfStudioUiEvent {
    data class SharePdf(val uri: Uri) : PdfStudioUiEvent()
    data class ViewPdf(val uri: Uri) : PdfStudioUiEvent()
    data class Error(val message: String) : PdfStudioUiEvent()
    data class PdfBuilt(val displayName: String, val pageCount: Int) : PdfStudioUiEvent()
}
