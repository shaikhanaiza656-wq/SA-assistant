package com.sa.assistant.ui.pdfmark

sealed class PdfMarkUiEvent {
    data class Error(val message: String) : PdfMarkUiEvent()
    data class Saved(val displayName: String, val pageCount: Int) : PdfMarkUiEvent()
}
