package com.sa.assistant.ui.tools

import android.content.Intent

sealed class ToolsUiEvent {
    data class Info(val message: String) : ToolsUiEvent()
    data class LaunchSystemIntent(val intent: Intent, val reason: String) : ToolsUiEvent()
}
