package com.sa.assistant.ui.pdfpages

sealed class PdfPageManagerUiEvent {
    data class Error(val message: String) : PdfPageManagerUiEvent()
    data class Saved(val displayName: String, val pageCount: Int) : PdfPageManagerUiEvent()
    data class Extracted(val displayName: String, val pageCount: Int) : PdfPageManagerUiEvent()
}
