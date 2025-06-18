package com.yogen.Android_Use.accessibility.service_files

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.yogen.Android_Use.accessibility.AndroidUseAccessibilityService

/**
 * Utility functions for working with AccessibilityNodeInfo objects.
 */

// Tag for logging within these utils
private const val NODE_UTILS_TAG = "NodeInfoUtils"

/**
 * Gets a simple identifier string for a node (class, resource ID, text).
 * Handles potential IllegalStateException if the node becomes invalid.
 */
fun getNodeIdentifier(node: AccessibilityNodeInfo?): String {
    if (node == null) return "null_node"
    return try {
        val className = node.className?.toString()?.substringAfterLast('.') ?: "UnknownClass"
        val resId = node.viewIdResourceName ?: "no_id"
        val text = node.text?.let { "\"${it.take(20)}${if (it.length > 20) "..." else ""}\"" } ?: "no_text"
        "$className($resId, $text)"
    } catch (e: IllegalStateException) {
        "invalid_node"
    }
}

/** Converts accessibility action constants to readable strings for logging. */
fun actionToString(action: Int): String {
    return when (action) {
        AccessibilityNodeInfo.ACTION_FOCUS -> "ACTION_FOCUS"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "ACTION_CLEAR_FOCUS"
        AccessibilityNodeInfo.ACTION_SELECT -> "ACTION_SELECT"
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "ACTION_CLEAR_SELECTION"
        AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "ACTION_ACCESSIBILITY_FOCUS"
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "ACTION_CLEAR_ACCESSIBILITY_FOCUS"
        AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY -> "ACTION_NEXT_AT_MOVEMENT_GRANULARITY"
        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> "ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY"
        AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT -> "ACTION_NEXT_HTML_ELEMENT"
        AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT -> "ACTION_PREVIOUS_HTML_ELEMENT"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
        AccessibilityNodeInfo.ACTION_COPY -> "ACTION_COPY"
        AccessibilityNodeInfo.ACTION_PASTE -> "ACTION_PASTE"
        AccessibilityNodeInfo.ACTION_CUT -> "ACTION_CUT"
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> "ACTION_SET_SELECTION"
        AccessibilityNodeInfo.ACTION_EXPAND -> "ACTION_EXPAND"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "ACTION_COLLAPSE"
        AccessibilityNodeInfo.ACTION_DISMISS -> "ACTION_DISMISS"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
        // Add others as needed
        else -> "UNKNOWN_ACTION ($action)"
    }
}