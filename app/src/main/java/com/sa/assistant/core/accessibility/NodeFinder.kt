package com.sa.assistant.core.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Real node-tree search over whatever [AccessibilityNodeInfo] tree the
 * currently connected [SaAccessibilityService] hands back. Every method
 * here walks the actual live tree at call time — nothing cached and
 * nothing guessed, so a result always reflects what's really on screen
 * the moment an automation action runs.
 */
object NodeFinder {

    /** First node whose visible text or content-description matches [text]. */
    fun findByText(root: AccessibilityNodeInfo?, text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        if (root == null) return null
        val candidates = root.findAccessibilityNodeInfosByText(text)
        return if (exact) {
            candidates.firstOrNull { node -> node.text?.toString() == text || node.contentDescription?.toString() == text }
        } else {
            candidates.firstOrNull()
        }
    }

    /** Search by real Android resource id, e.g. "com.whatsapp:id/send". */
    fun findByResourceId(root: AccessibilityNodeInfo?, resourceId: String): AccessibilityNodeInfo? {
        if (root == null) return null
        return root.findAccessibilityNodeInfosByViewId(resourceId).firstOrNull()
    }

    /** Walks up from [node] to the nearest ancestor that is actually clickable — many text/icon nodes sit inside a clickable row. */
    fun nearestClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    /** Every editable field currently on screen, in traversal order — used to pick "the message box" when more than one field exists. */
    fun findAllEditable(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isEditable) result.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return result
    }
}
