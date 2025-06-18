package com.yogen.Android_Use.accessibility.service_files

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.yogen.Android_Use.accessibility.walk
import com.yogen.Android_Use.accessibility.toSelector
import com.yogen.Android_Use.models.Selector

// --- REMOVE DUPLICATE CONSTANTS --- //
// private const val MIN_ELEMENT_SIZE = 1 // Defined in AccessibilityConstants.kt
// private const val SERVICE_TAG = "AccessibilityDataRetriever" // Give it a unique tag
// -------------------------------- //

/**
 * Contains functions to retrieve accessibility node data as Selector lists.
 */

// Helper to get root node safely and handle recycling
// Returns null if root is invalid/inaccessible, otherwise Pair<Selectors, Nodes>
private fun useRootNode(
    service: AccessibilityService,
    nodeProcessor: (AccessibilityNodeInfo) -> Pair<List<Selector>, List<AccessibilityNodeInfo>>
): Pair<List<Selector>, List<AccessibilityNodeInfo>> {
    var rootNode: AccessibilityNodeInfo? = null
    return try {
        rootNode = service.rootInActiveWindow
        if (rootNode == null) {
            Log.w(SERVICE_TAG, "Root node is null. Cannot perform node retrieval.")
            Pair(emptyList(), emptyList()) // Return empty pair
        } else {
            // Ensure the root node is valid before using it
             try {
                 if (!rootNode.refresh()) {
                     Log.w(SERVICE_TAG, "Root node failed to refresh. Assuming invalid.")
                     Pair(emptyList(), emptyList()) // Return empty pair
                 } else {
                    nodeProcessor(rootNode) // Execute the processing lambda
                 }
             } catch (e: IllegalStateException) {
                  Log.w(SERVICE_TAG, "Root node became invalid during refresh check: ${e.message}")
                  Pair(emptyList(), emptyList()) // Return empty pair
             }
        }
    } catch (e: Exception) {
        Log.e(SERVICE_TAG, "Exception getting or using root node: ${e.message}", e)
        Pair(emptyList(), emptyList()) // Return empty pair on any exception
    } finally {
        // Recycle the original root node obtained from the service
        try {
            rootNode?.recycle()
        } catch (e: IllegalStateException) {
            Log.w(SERVICE_TAG, "Failed to recycle root node (might already be invalid): ${e.message}")
        }
    }
}

/**
 * Retrieves all accessibility nodes from the active window.
 *
 * @param service The AccessibilityService instance.
 * @return A Pair containing the list of Selectors and the corresponding list of raw AccessibilityNodeInfo objects.
 */
fun getAllNodes(service: AccessibilityService): Pair<List<Selector>, List<AccessibilityNodeInfo>> {
    Log.d(SERVICE_TAG, "Retrieving all nodes...")
    return useRootNode(service) { rootNode ->
        val collectedNodes = mutableListOf<AccessibilityNodeInfo>()
        rootNode.walk { node ->
            collectedNodes.add(AccessibilityNodeInfo.obtain(node)) // Obtain a copy to add
        }
        Log.d(SERVICE_TAG, "Walk completed. Found ${collectedNodes.size} nodes.")

        // Create selectors, but keep the original nodes separate
        val selectors = collectedNodes.mapNotNull { node ->
            try {
                node.toSelector()
            } catch (e: IllegalStateException) {
                Log.w(SERVICE_TAG, "Node became invalid when creating selector for getAllNodes")
                null
            }
            // DO NOT recycle here, the raw nodes list needs to be returned
        }
        Log.d(SERVICE_TAG, "Created ${selectors.size} selectors.")
        // Return both lists
        Pair(selectors, collectedNodes)
    }
}

/**
 * Finds nodes containing the specified text within the active window.
 *
 * @param service The AccessibilityService instance.
 * @param searchText The text to search for.
 * @return A Pair containing the list of Selectors for matching nodes and the corresponding list of raw AccessibilityNodeInfo objects.
 */
fun findNodesByText(service: AccessibilityService, searchText: String): Pair<List<Selector>, List<AccessibilityNodeInfo>> {
    Log.d(SERVICE_TAG, "Finding nodes by text: '$searchText'")
    return useRootNode(service) { rootNode ->
        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        rootNode.walk { node ->
            var nodeText: CharSequence? = null
            var nodeDesc: CharSequence? = null
            var visible = false
            var refreshed = false
            try {
                refreshed = node.refresh()
                if (!refreshed) return@walk // Skip if refresh fails
                nodeText = node.text
                nodeDesc = node.contentDescription
                visible = node.isVisibleToUser
            } catch (e: IllegalStateException) {
                return@walk // Skip if node becomes invalid
            }

            if (visible && (nodeText?.contains(searchText, ignoreCase = true) == true ||
                    nodeDesc?.contains(searchText, ignoreCase = true) == true)) {
                 matchingNodes.add(AccessibilityNodeInfo.obtain(node)) // Store a copy
            }
        }
        Log.d(SERVICE_TAG, "Walk completed. Found ${matchingNodes.size} nodes matching text '$searchText'.")

        val selectors = matchingNodes.mapNotNull { node ->
            try {
                 node.toSelector()
            } catch (e: IllegalStateException) {
                Log.w(SERVICE_TAG, "Node became invalid when creating selector for findNodesByText")
                null
            }
             // DO NOT recycle here, the raw nodes list needs to be returned
        }
        Log.d(SERVICE_TAG, "Created ${selectors.size} selectors for text search '$searchText'.")
        Pair(selectors, matchingNodes)
    }
}

/**
 * Retrieves all interactive accessibility nodes from the active window.
 *
 * @param service The AccessibilityService instance.
 * @return A Pair containing the list of Selectors for interactive nodes and the corresponding list of raw AccessibilityNodeInfo objects.
 */
fun getAllInteractiveNodes(service: AccessibilityService): Pair<List<Selector>, List<AccessibilityNodeInfo>> {
    Log.d(SERVICE_TAG, "Retrieving interactive nodes...")
    return useRootNode(service) { rootNode ->
        val interactiveNodes = mutableListOf<AccessibilityNodeInfo>()
        rootNode.walk { node ->
            var isInteractive = false
            var visible = false
             var refreshed = false
            try {
                 refreshed = node.refresh()
                 if (!refreshed) return@walk // Skip if refresh fails
                 isInteractive = node.isClickable || node.isLongClickable || node.isFocusable
                 visible = node.isVisibleToUser
            } catch (e: IllegalStateException) {
                 return@walk // Skip if node becomes invalid
            }

            if (visible && isInteractive) {
                interactiveNodes.add(AccessibilityNodeInfo.obtain(node)) // Store a copy
            }
        }
        Log.d(SERVICE_TAG, "Walk completed. Found ${interactiveNodes.size} interactive nodes.")

         val selectors = interactiveNodes.mapNotNull { node ->
             try {
                 node.toSelector()
             } catch (e: IllegalStateException) {
                 Log.w(SERVICE_TAG, "Node became invalid when creating selector for getAllInteractiveNodes")
                 null
             }
             // DO NOT recycle here, the raw nodes list needs to be returned
         }
        Log.d(SERVICE_TAG, "Created ${selectors.size} selectors for interactive nodes.")
        Pair(selectors, interactiveNodes)
    }
}

/**
 * Retrieves all clickable or long-clickable accessibility nodes from the active window.
 *
 * @param service The AccessibilityService instance.
 * @return A Pair containing the list of Selectors for clickable nodes and the corresponding list of raw AccessibilityNodeInfo objects.
 */
fun getAllClickableNodes(service: AccessibilityService): Pair<List<Selector>, List<AccessibilityNodeInfo>> {
    Log.d(SERVICE_TAG, "Retrieving clickable nodes...")
    return useRootNode(service) { rootNode ->
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        rootNode.walk { node ->
            var isClickable = false
            var visible = false
            var refreshed = false
            try {
                refreshed = node.refresh()
                if (!refreshed) return@walk // Skip if refresh fails
                // Filter specifically for clickable or long-clickable
                isClickable = node.isClickable || node.isLongClickable
                visible = node.isVisibleToUser
            } catch (e: IllegalStateException) {
                return@walk // Skip if node becomes invalid
            }

            if (visible && isClickable) {
                clickableNodes.add(AccessibilityNodeInfo.obtain(node)) // Store a copy
            }
        }
        Log.d(SERVICE_TAG, "Walk completed. Found ${clickableNodes.size} clickable nodes.")

        val selectors = clickableNodes.mapNotNull { node ->
            try {
                node.toSelector()
            } catch (e: IllegalStateException) {
                Log.w(SERVICE_TAG, "Node became invalid when creating selector for getAllClickableNodes")
                null
            }
            // DO NOT recycle here, the raw nodes list needs to be returned
        }
        Log.d(SERVICE_TAG, "Created ${selectors.size} selectors for clickable nodes.")
        Pair(selectors, clickableNodes)
    }
}

// REMOVED: JSON Serialization Helper

// REMOVED: filterNodesByTextRecursive function

// REMOVED: filterInteractiveNodesRecursive function

// REMOVED: INTERACTIVE_TYPES set 