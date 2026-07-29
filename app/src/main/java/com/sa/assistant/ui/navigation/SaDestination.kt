package com.sa.assistant.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom navigation destinations. Matches the 5-tab layout from the
 * mockup: Home, Chat, PDF, Tools, Settings. PDF Studio (Phase 3 Part 1:
 * camera/gallery scan-to-PDF; Phase 3 Part 2A: merge/split/rotate/
 * reorder/delete via [com.sa.assistant.ui.pdfpages.PdfPageManagerScreen];
 * Phase 3 Part 2B part 1 of 2: highlight/underline/strikethrough/
 * free-hand draw with undo/redo via
 * [com.sa.assistant.ui.pdfmark.PdfMarkScreen]) is now real — see
 * [com.sa.assistant.ui.pdfstudio.PdfStudioScreen]. Tools (Phase 4:
 * volume/brightness/flashlight/bluetooth/music/app launch) is also now
 * real — see [com.sa.assistant.ui.tools.ToolsScreen]. Settings (Phase 6:
 * wake word live now, voice verification/STT/TTS/memory landing here in
 * later Phase 6 parts) is also real — see
 * [com.sa.assistant.ui.settings.SettingsScreen].
 */
sealed class SaDestination(val route: String, val label: String, val icon: ImageVector) {
    data object Home : SaDestination("home", "Home", Icons.Filled.Home)
    data object Chat : SaDestination("chat", "Chat", Icons.Filled.Chat)
    data object Pdf : SaDestination("pdf", "PDF", Icons.Filled.PictureAsPdf)
    data object Tools : SaDestination("tools", "Tools", Icons.Filled.Build)
    data object Settings : SaDestination("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val bottomBarItems = listOf(Home, Chat, Pdf, Tools, Settings)
    }
}
