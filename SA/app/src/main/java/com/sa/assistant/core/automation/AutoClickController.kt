package com.sa.assistant.core.automation

import com.sa.assistant.core.accessibility.AccessibilityPermissionHelper
import com.sa.assistant.core.accessibility.GestureDispatcher
import com.sa.assistant.core.accessibility.NodeFinder
import com.sa.assistant.core.accessibility.SaAccessibilityService
import com.sa.assistant.data.model.AutomationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 5's fourth, general-purpose automation — the poster's "AutoClick"
 * item. Unlike [com.sa.assistant.core.automation.social.WhatsAppAutomation]
 * / InstagramAutomation / YouTubeAutomation, this doesn't launch any
 * specific app: it taps whatever is already visible on screen right now
 * that matches [text] — the same real node-tree lookup + real
 * [android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK] dispatch
 * those three use, just not scoped to one package.
 *
 * That's an honest, deliberate limit: it acts on the screen the user (or
 * a prior automation step) already has open, not on a screen it goes and
 * finds — "auto click" here means "find and press this on my current
 * screen," not "navigate anywhere for me."
 */
@Singleton
class AutoClickController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: AccessibilityPermissionHelper
) {
    /** Real click on the current screen's node whose visible text or content-description matches [text]. */
    fun tap(text: String): AutomationResult {
        if (!permissionHelper.isEnabled() || !SaAccessibilityService.isRunning) {
            return AutomationResult.AccessibilityServiceOff
        }
        val root = SaAccessibilityService.currentRootNode()
            ?: return AutomationResult.Failed("Abhi koi screen padh nahi paya — kuch bhi khula hona chahiye")

        val target = NodeFinder.findByText(root, text, exact = false)
            ?: return AutomationResult.Failed("\"$text\" jaisa kuch is screen par nahi mila")

        return if (GestureDispatcher.click(target)) {
            AutomationResult.Success
        } else {
            AutomationResult.Failed("\"$text\" par click nahi ho paya — clickable nahi hai shayad")
        }
    }
}
