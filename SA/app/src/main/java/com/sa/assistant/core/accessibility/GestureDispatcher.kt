package com.sa.assistant.core.accessibility

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Real click/text-entry dispatch on top of whatever node
 * [SaAccessibilityService] currently sees — every call here performs an
 * actual [AccessibilityNodeInfo] action; nothing here is simulated.
 */
object GestureDispatcher {

    /** Real click via ACTION_CLICK, falling back to the nearest clickable ancestor if [node] itself isn't directly clickable. */
    fun click(node: AccessibilityNodeInfo?): Boolean {
        val target = node?.let { if (it.isClickable) it else NodeFinder.nearestClickableAncestor(it) }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** Real text entry via ACTION_SET_TEXT — this is what actually fills a field, not a fake key-by-key simulation. */
    fun setText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null || !node.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Real "press enter on the keyboard" for a search field — submits via
     * ACTION_IME_ENTER, same as a person tapping the keyboard's search key.
     *
     * `ACTION_IME_ENTER` isn't a plain int constant on [AccessibilityNodeInfo]
     * — it only exists as [AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER]
     * (its `.id` is what [AccessibilityNodeInfo.performAction] actually takes),
     * and that action was only added in API 30 (Android 11). minSdk here is 26,
     * so on API 26–29 devices there is no equivalent standalone IME-submit
     * action to call — rather than fake success, this honestly returns false
     * on those OS versions instead of pretending the submit happened.
     */
    fun submitIme(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } catch (e: Exception) {
            false
        }
    }
}
