package com.yogen.Android_Use.accessibility.service_files

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Queue
import java.util.LinkedList

// --- Node Finding Functions ---

/**
 * Find a node by its index in the UI Dump *traversal order*.
 * This assumes the UI hasn't changed significantly since the dump was conceptually generated.
 * Returns a COPY of the found node. Caller does not need to recycle.
 * Handles recycling internally.
 * Requires the service instance to access rootInActiveWindow.
 * 
 * **NOTE:** This function needs review and integration with AccessibilityDataRetriever's node list.
 * It currently traverses the live hierarchy, which might not match the indices from the list
 * sent to the server.
 */
fun findNodeByIndex(service: android.accessibilityservice.AccessibilityService, targetIndex: Int): AccessibilityNodeInfo? {
    var root : AccessibilityNodeInfo? = null
    var foundNodeCopy: AccessibilityNodeInfo? = null
    var currentIndex = -1 // Use a local var, not a member var

    try {
        root = service.rootInActiveWindow ?: return null

        fun findRecursive(node: AccessibilityNodeInfo?): Boolean {
            if (node == null || foundNodeCopy != null) return false // Stop if found or node is null

            val nodeCopyForProcessing = AccessibilityNodeInfo.obtain(node) // Make copy for safe access
            var foundTarget = false
            try {
                currentIndex++
                val isTarget = (currentIndex == targetIndex)

                if (isTarget) {
                    foundNodeCopy = AccessibilityNodeInfo.obtain(nodeCopyForProcessing) // Important: Obtain a copy for the result
                    foundTarget = true
                    // We found the target, no need to traverse its children for *this* search
                } else {
                    // If not the target, traverse children
                    for (i in 0 until nodeCopyForProcessing.childCount) {
                        val child = nodeCopyForProcessing.getChild(i)
                        if (child != null) {
                            if (findRecursive(child)) { // Pass original child ref down
                                foundTarget = true
                                // Child was the target or led to the target.
                                // The recursive call handles obtaining the copy.
                                // We don't need to recycle 'child' here.
                                break // Stop searching siblings if found
                            }
                            // No need to recycle 'child' here if findRecursive didn't find target
                        }
                    }
                }
            } finally {
                nodeCopyForProcessing.recycle() // Recycle the copy made for processing this level
            }
            return foundTarget // Return whether found in this branch
        }

        findRecursive(root)

    } catch (e: Exception) {
        Log.e(SERVICE_TAG, "Exception in findNodeByIndex: ${e.message}", e)
        foundNodeCopy?.recycle() // Recycle the copy if error occurred after finding it
        foundNodeCopy = null
    } finally {
        root?.let { try { it.recycle() } catch (re: IllegalStateException) { /* ignore */ } }
    }
    return foundNodeCopy // Return the copy or null
}
