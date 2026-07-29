package com.sa.assistant.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 5: Accessibility Automation — the real [AccessibilityService]
 * that WhatsApp/Instagram/YouTube automation (Part 2:
 * [com.sa.assistant.core.automation.social]) and the Automation UI
 * (Part 3: [com.sa.assistant.ui.tools.ToolsScreen]) both depend on.
 *
 * Turning this on requires the user to flip it on manually from
 * Settings > Accessibility — Android does not let an app silently
 * enable its own accessibility service, by design, since a connected
 * service can read and act on the whole screen. See
 * [AccessibilityPermissionHelper] for the honest, no-shortcut way this
 * app checks for / asks for that.
 *
 * This service itself does nothing automatically: it only keeps a live
 * reference to itself ([instance]) so the rest of the app can pull the
 * current screen's real node tree ([currentRootNode]) and dispatch real
 * clicks/text-entry/gestures on demand — only when the user explicitly
 * triggers one specific automation action from the Tools tab. There is
 * no background polling loop and no event is acted on automatically.
 */
class SaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately a no-op. This service is used on-demand (pulled via
        // currentRootNode() only when a user taps an automation button)
        // rather than reacting to every window/content-change event, so
        // it never does anything the user didn't just explicitly ask for.
    }

    override fun onInterrupt() {
        // Required override. Nothing to clean up since no background work runs.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    companion object {
        @Volatile private var instance: SaAccessibilityService? = null

        /** True only once Android has actually connected the service (permission granted + toggled on). */
        val isRunning: Boolean get() = instance != null

        /** Real, live root node of whatever screen is currently in front — null if the service isn't connected. */
        fun currentRootNode(): AccessibilityNodeInfo? = instance?.rootInActiveWindow

        /** Real global system action (e.g. GLOBAL_ACTION_BACK) — false if the service isn't connected. */
        fun globalAction(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }
}
