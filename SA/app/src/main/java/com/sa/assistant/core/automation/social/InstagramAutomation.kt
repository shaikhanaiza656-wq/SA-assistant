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
 * Real Instagram "open the app and like the first visible feed post"
 * automation. Instagram doesn't expose stable resource ids across app
 * versions the way WhatsApp partially does, so this matches purely by
 * the "Like" content-description, which Meta has kept far more
 * consistent — but if that changes, this returns
 * [AutomationResult.Failed] with a specific reason instead of silently
 * doing nothing or pretending to succeed.
 */
@Singleton
class InstagramAutomation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: AccessibilityPermissionHelper
) {
    suspend fun likeFirstFeedPost(): AutomationResult {
        if (!permissionHelper.isEnabled() || !SaAccessibilityService.isRunning) {
            return AutomationResult.AccessibilityServiceOff
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE)
            ?: return AutomationResult.TargetAppNotInstalled

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        delay(APP_OPEN_DELAY_MS)

        val root = SaAccessibilityService.currentRootNode()
            ?: return AutomationResult.Failed("Instagram ka screen padh nahi paya")

        val likeButton = NodeFinder.findByText(root, "Like", exact = true)
            ?: return AutomationResult.Failed("Like button nahi mila — feed abhi load ho raha ho sakta hai")

        return if (GestureDispatcher.click(likeButton)) {
            AutomationResult.Success
        } else {
            AutomationResult.Failed("Like par click nahi ho paya")
        }
    }

    companion object {
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val APP_OPEN_DELAY_MS = 2000L
    }
}
