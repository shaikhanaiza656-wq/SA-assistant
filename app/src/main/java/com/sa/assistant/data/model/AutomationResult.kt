package com.sa.assistant.data.model

/**
 * Phase 5: Accessibility Automation — real outcome of one automation run
 * in [com.sa.assistant.core.automation.social]. Never a guess: each
 * value reflects something the app actually just checked or attempted
 * on the live screen.
 */
sealed class AutomationResult {
    /** Every step really found its node and really clicked/typed on it. */
    data object Success : AutomationResult()

    /** A specific step failed — [reason] names which one, in Hinglish, for the snackbar. */
    data class Failed(val reason: String) : AutomationResult()

    /** The user hasn't turned on SA's Accessibility Service in system Settings yet. */
    data object AccessibilityServiceOff : AutomationResult()

    /** The target app (WhatsApp/Instagram/YouTube) isn't installed on this device. */
    data object TargetAppNotInstalled : AutomationResult()
}
