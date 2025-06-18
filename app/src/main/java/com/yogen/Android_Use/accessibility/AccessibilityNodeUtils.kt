package com.yogen.Android_Use.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.yogen.Android_Use.models.Selector

// ------------- REMOVED transport model sent to the server -------------
// REMOVED: data class NodePayload(...) 

// ---------- tree utilities (depth-first traversal) -------------

/**
 * Performs a depth-first walk of the node tree, applying the action to each node.
 */
fun AccessibilityNodeInfo.walk(action: (AccessibilityNodeInfo) -> Unit) {
    // Use try-catch to handle potential IllegalStateException if a node becomes invalid during traversal
    try {
        action(this)
        for (i in 0 until this.childCount) {
            val child = this.getChild(i)
            child?.walk(action) // Recursively walk children
            // It's generally safer NOT to explicitly recycle nodes obtained from getChild() here,
            // as the system manages their lifecycle within the context of the parent.
            // Explicit recycling can lead to errors if the node is still needed elsewhere.
        }
    } catch (e: IllegalStateException) {
        // Log the error, but continue traversal if possible or just return
        // Consider logging the specific node identifier if available before the exception
        android.util.Log.w("AccessibilityNodeUtils", "Node became invalid during walk: ${e.message}")
    }
}


/**
 * Converts an AccessibilityNodeInfo to the Selector format for sending over WebSocket.
 * This provides more stable identification features than a simple index.
 */
fun AccessibilityNodeInfo.toSelector(): Selector {
    // Capture bounds safely
    val currentBounds = Rect()
    try {
        this.getBoundsInScreen(currentBounds)
    } catch (e: IllegalStateException) {
        android.util.Log.w("AccessibilityNodeUtils", "Node became invalid getting bounds: ${e.message}. Using empty bounds.")
        // Assign empty or default bounds if node is invalid
    }

    // Safely access properties needed for the Selector
    val nodeText = try { this.text?.toString() } catch (e: IllegalStateException) { null }
    val nodeContentDesc = try { this.contentDescription?.toString() } catch (e: IllegalStateException) { null }
    val nodeViewId = try { this.viewIdResourceName } catch (e: IllegalStateException) { null }
    val nodeClassName = try { this.className?.toString() } catch (e: IllegalStateException) { null }
    val nodeWindowId = try { this.windowId } catch (e: IllegalStateException) { -1 }
    // Properties for verification/filtering
    val nodeIsClickable = try { this.isClickable } catch (e: IllegalStateException) { false }
    val nodeIsEditable = try { this.isEditable } catch (e: IllegalStateException) { false }
    val nodeIsLongClickable = try { this.isLongClickable } catch (e: IllegalStateException) { false }

    // TODO: Implement getSourceNodeId() reflection logic if needed later.

    return Selector(
        viewId = nodeViewId,
        text = nodeText,
        contentDesc = nodeContentDesc,
        className = nodeClassName,
        windowId = nodeWindowId,
        bounds = if (currentBounds.isEmpty) null else currentBounds,
        isClickable = nodeIsClickable,
        isEditable = nodeIsEditable,
        isLongClickable = nodeIsLongClickable
    )
} 