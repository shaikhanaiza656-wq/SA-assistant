package com.sa.assistant.core.automation.social

import android.content.Context
import android.content.Intent
import com.sa.assistant.core.accessibility.AccessibilityPermissionHelper
import com.sa.assistant.core.accessibility.GestureDispatcher
import com.sa.assistant.core.accessibility.NodeFinder
import com.sa.assistant.core.accessibility.SaAccessibilityService
import com.sa.assistant.data.model.AutomationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real YouTube "search a query and open the first result" automation,
 * again entirely through [SaAccessibilityService]'s live node tree — the
 * same search icon, search field and result row a person taps.
 *
 * Honest limit: after typing the query this submits the same way the
 * keyboard's own search key does ([GestureDispatcher.submitIme]) rather
 * than guessing at a submit button, since YouTube's search bar doesn't
 * expose a separate clickable "go" icon on most devices/keyboards.
 */
@Singleton
class YouTubeAutomation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: AccessibilityPermissionHelper
) {
    suspend fun searchAndPlay(query: String): AutomationResult {
        if (!permissionHelper.isEnabled() || !SaAccessibilityService.isRunning) {
            return AutomationResult.AccessibilityServiceOff
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
            ?: return AutomationResult.TargetAppNotInstalled

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        delay(APP_OPEN_DELAY_MS)

        val rootAfterOpen = SaAccessibilityService.currentRootNode()
            ?: return AutomationResult.Failed("YouTube ka screen padh nahi paya")

        val searchIcon = NodeFinder.findByResourceId(rootAfterOpen, "$YOUTUBE_PACKAGE:id/menu_item_1")
            ?: NodeFinder.findByText(rootAfterOpen, "Search")
            ?: return AutomationResult.Failed("Search icon nahi mila")
        if (!GestureDispatcher.click(searchIcon)) {
            return AutomationResult.Failed("Search par click nahi ho paya")
        }
        delay(SHORT_DELAY_MS)

        val searchField = NodeFinder.findAllEditable(SaAccessibilityService.currentRootNode()).firstOrNull()
            ?: return AutomationResult.Failed("Search field nahi mila")
        if (!GestureDispatcher.setText(searchField, query)) {
            return AutomationResult.Failed("Query type nahi ho paya")
        }
        delay(SHORT_DELAY_MS)
        GestureDispatcher.submitIme(searchField)
        delay(RESULTS_DELAY_MS)

        val firstResult = NodeFinder.findByResourceId(SaAccessibilityService.currentRootNode(), "$YOUTUBE_PACKAGE:id/title")
            ?: return AutomationResult.Failed("Koi result nahi mila")

        return if (GestureDispatcher.click(firstResult)) {
            AutomationResult.Success
        } else {
            AutomationResult.Failed("Video par click nahi ho paya")
        }
    }

    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val APP_OPEN_DELAY_MS = 1800L
        private const val SHORT_DELAY_MS = 700L
        private const val RESULTS_DELAY_MS = 1200L
    }
}
