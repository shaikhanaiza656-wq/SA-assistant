package com.sa.assistant.ui.settings

sealed class SettingsUiEvent {
    data class Info(val message: String) : SettingsUiEvent()
}
