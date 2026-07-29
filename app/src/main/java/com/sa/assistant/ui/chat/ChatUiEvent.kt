package com.sa.assistant.ui.chat

import android.net.Uri

sealed class ChatUiEvent {
    data class ShareText(val text: String) : ChatUiEvent()
    data class SharePdf(val uri: Uri) : ChatUiEvent()
    data object CopiedToClipboard : ChatUiEvent()
    data class Error(val message: String) : ChatUiEvent()
}
