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
 * Real WhatsApp "search a contact, open the chat, type a message, hit
 * Send" automation — driven entirely through [SaAccessibilityService]'s
 * live node tree, the exact same search box, contact row, message field
 * and Send button a person would tap themselves.
 *
 * Honest limits, stated up front rather than hidden:
 * - WhatsApp's internal resource ids can change between app updates.
 *   Each step tries the resource id first, then falls back to matching
 *   by visible text/content-description — but a WhatsApp redesign can
 *   still break a step. When that happens this returns
 *   [AutomationResult.Failed] with the specific step name, never a fake
 *   [AutomationResult.Success].
 * - It waits on real fixed delays for WhatsApp's own UI to draw after
 *   each step (cold app start, opening search, opening a chat), since
 *   there's no other reliable signal for "the next screen finished
 *   rendering."
 * - Needs the real Accessibility permission on ([AccessibilityPermissionHelper])
 *   and WhatsApp actually installed.
 */
@Singleton
class WhatsAppAutomation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: AccessibilityPermissionHelper
) {
    suspend fun sendMessage(contactName: String, message: String): AutomationResult {
        if (!permissionHelper.isEnabled() || !SaAccessibilityService.isRunning) {
            return AutomationResult.AccessibilityServiceOff
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)
            ?: return AutomationResult.TargetAppNotInstalled

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        delay(APP_OPEN_DELAY_MS)

        val rootAfterOpen = SaAccessibilityService.currentRootNode()
            ?: return AutomationResult.Failed("WhatsApp ka screen padh nahi paya")

        val searchIcon = NodeFinder.findByResourceId(rootAfterOpen, "$WHATSAPP_PACKAGE:id/menuitem_search")
            ?: NodeFinder.findByText(rootAfterOpen, "Search")
            ?: return AutomationResult.Failed("Search button nahi mila")
        if (!GestureDispatcher.click(searchIcon)) {
            return AutomationResult.Failed("Search par click nahi ho paya")
        }
        delay(SHORT_DELAY_MS)

        val searchField = NodeFinder.findAllEditable(SaAccessibilityService.currentRootNode()).firstOrNull()
            ?: return AutomationResult.Failed("Search field nahi mila")
        if (!GestureDispatcher.setText(searchField, contactName)) {
            return AutomationResult.Failed("Contact naam type nahi ho paya")
        }
        delay(SHORT_DELAY_MS)

        val contactRow = NodeFinder.findByText(SaAccessibilityService.currentRootNode(), contactName)
            ?: return AutomationResult.Failed("\"$contactName\" naam ka contact nahi mila")
        if (!GestureDispatcher.click(contactRow)) {
            return AutomationResult.Failed("Contact par click nahi ho paya")
        }
        delay(SHORT_DELAY_MS)

        val messageField = NodeFinder.findAllEditable(SaAccessibilityService.currentRootNode())
            .firstOrNull { it.text.isNullOrEmpty() }
            ?: return AutomationResult.Failed("Message box nahi mila")
        if (!GestureDispatcher.setText(messageField, message)) {
            return AutomationResult.Failed("Message type nahi ho paya")
        }
        delay(SHORT_DELAY_MS)

        val sendButton = NodeFinder.findByResourceId(SaAccessibilityService.currentRootNode(), "$WHATSAPP_PACKAGE:id/send")
            ?: NodeFinder.findByText(SaAccessibilityService.currentRootNode(), "Send")
            ?: return AutomationResult.Failed("Send button nahi mila")

        return if (GestureDispatcher.click(sendButton)) {
            AutomationResult.Success
        } else {
            AutomationResult.Failed("Send par click nahi ho paya")
        }
    }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val APP_OPEN_DELAY_MS = 1500L
        private const val SHORT_DELAY_MS = 700L
    }
}
