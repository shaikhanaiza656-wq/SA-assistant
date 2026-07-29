package com.sa.assistant.ui.chat

import android.content.Intent
import android.net.Uri

sealed class ChatUiEvent {
    data class ShareText(val text: String) : ChatUiEvent()
    data class SharePdf(val uri: Uri) : ChatUiEvent()
    data object CopiedToClipboard : ChatUiEvent()
    data class Error(val message: String) : ChatUiEvent()

    /** A chat-driven automation command (see [AutomationCommandExecutor]) needs a real
     *  system screen — e.g. the "Modify system settings" permission screen, or Android's
     *  own Bluetooth on/off dialog — launched from the Activity. */
    data class LaunchSystemIntent(val intent: Intent) : ChatUiEvent()
}
