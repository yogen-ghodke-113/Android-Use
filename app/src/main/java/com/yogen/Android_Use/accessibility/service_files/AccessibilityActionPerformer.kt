package com.yogen.Android_Use.accessibility.service_files

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.yogen.Android_Use.execution.engine_files.getNodeIdentifier
import com.yogen.Android_Use.accessibility.service_files.SERVICE_TAG
import android.content.res.Resources
import com.yogen.Android_Use.utils.DeviceMetricsHolder

// --- Action Performing Functions ---

/**
 * Click on a specific node. Attempts to click parent if node is not clickable.
 * Returns true if a click action was successfully performed on the node or a clickable parent.
 */
/**
 * Performs ACTION_CLICK on the provided node.
 * Assumes the caller has already identified the correct clickable node.
 * Returns true if the click action was successfully performed.
 */
suspend fun performClickOnNode(service: AccessibilityService, node: AccessibilityNodeInfo?): Boolean {
    if (node == null) {
        Log.w(SERVICE_TAG, "performClickOnNode called with null node.")
        return false
    }

    var nodeCopy: AccessibilityNodeInfo? = null
    try {
        // Obtain a fresh copy to work with
        nodeCopy = AccessibilityNodeInfo.obtain(node)
        if (nodeCopy == null) {
            Log.e(SERVICE_TAG, "Failed to obtain copy of node for click: ${getNodeIdentifier(node)}")
            return false
        }

        // Refresh the copy immediately before acting on it
        nodeCopy.refresh()
        if (!nodeCopy.isVisibleToUser) { // Check visibility again on the refreshed copy
            Log.w(SERVICE_TAG, "Node copy became invisible after refresh in performClickOnNode: ${getNodeIdentifier(nodeCopy)}")
            return false // Don't attempt click if invisible
        }

        // Get bounds and calculate center
        val nodeBounds = Rect()
        nodeCopy.getBoundsInScreen(nodeBounds)

        if (nodeBounds.isEmpty) {
            Log.e(SERVICE_TAG, "Node copy for click has empty bounds. Cannot dispatch gesture.")
            return false 
        }

        val centerX = nodeBounds.centerX().toFloat()
        val centerY = nodeBounds.centerY().toFloat()

        // Basic sanity check for coordinates
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        if (centerX < 0 || centerY < 0 || centerX > screenWidth || centerY > screenHeight) {
             Log.w(SERVICE_TAG, "Calculated center ($centerX, $centerY) is off-screen ($screenWidth x $screenHeight). Cannot dispatch gesture.")
             return false
         }

        Log.d(SERVICE_TAG, "Dispatching click gesture via coordinates ($centerX, $centerY) for node: ${getNodeIdentifier(nodeCopy)}")
        
        // Use dispatchGesture via clickAtSuspend instead of performAction
        return clickAtSuspend(service, centerX, centerY)

    } catch (e: IllegalStateException) {
        // This catch might still be relevant if obtain() or refresh() fails
        Log.e(SERVICE_TAG, "Node became invalid during click attempt (obtain/refresh): ${getNodeIdentifier(nodeCopy ?: node)}", e)
        return false
    } catch (e: Exception) {
        Log.e(SERVICE_TAG, "Unexpected error during click processing (bounds/dispatch): ${getNodeIdentifier(nodeCopy ?: node)}", e)
        return false
    } finally {
        // Always recycle the copy we made
        try {
            nodeCopy?.recycle()
        } catch (re: IllegalStateException) {
            Log.w(SERVICE_TAG, "Failed to recycle nodeCopy in performClickOnNode, possibly already recycled.")
        }
    }
}


/**
 * Click at coordinates with gesture. Uses a callback for completion/cancellation.
 */
fun clickAt(service: AccessibilityService, x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
    val path = Path()
    path.moveTo(x, y)

    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, 50)) // Short duration for tap
        .build()

    val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            super.onCompleted(gestureDescription)
            Log.d(SERVICE_TAG, "Click at ($x, $y) completed")
            callback?.invoke(true)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            super.onCancelled(gestureDescription)
            Log.w(SERVICE_TAG, "Click at ($x, $y) cancelled")
            callback?.invoke(false)
        }
    }, null) // Handler can be null

    if (!dispatched) {
        Log.e(SERVICE_TAG, "Failed to dispatch click gesture at ($x, $y)")
        callback?.invoke(false) // Invoke callback immediately if dispatch fails
    }
}

/**
 * Suspending version of clickAt for use with coroutines.
 */
suspend fun clickAtSuspend(service: AccessibilityService, x: Float, y: Float): Boolean = suspendCancellableCoroutine { continuation ->
    clickAt(service, x, y) { success ->
        if (continuation.isActive) {
            continuation.resume(success)
        }
    }
    // Handle cancellation if needed
    continuation.invokeOnCancellation {
        Log.d(SERVICE_TAG, "clickAtSuspend coroutine cancelled for ($x, $y)")
    }
}

/**
 * Perform scroll action on a specific node or its scrollable parent.
 * Returns true if the action was attempted on a scrollable node, false otherwise.
 * Note: ACTION_SCROLL_... often returns false even on success.
 * 
 * Renamed from performScrollOnNode to avoid conflict with the same function in AccessibilityNodeInfoUtils.kt
 */
fun performScrollAction(node: AccessibilityNodeInfo?, direction: Int): Boolean {
    if (node == null) return false

    var scrollableNode: AccessibilityNodeInfo? = null
    var tempNode: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node) // Work with a copy

    // Find the first scrollable ancestor (or self)
    while(tempNode != null) {
        if (tempNode.isScrollable) {
            scrollableNode = tempNode // Found it, keep this copy
            break
        }
        val parent = tempNode.parent
        tempNode.recycle() // Recycle the non-scrollable node
        tempNode = parent // Move up
    }

    if (scrollableNode == null) {
        Log.w(SERVICE_TAG, "Node and its parents are not scrollable: $node")
        return false // No scrollable node found
    }

    var result = false
    try {
        Log.d(SERVICE_TAG, "Performing scroll action $direction on scrollable node: $scrollableNode")
        result = scrollableNode.performAction(direction)
        Log.d(SERVICE_TAG, "Scroll action result: $result") // Log the actual result
    } catch (e: Exception) {
        Log.e(SERVICE_TAG, "Exception performing scroll on node", e)
    } finally {
        scrollableNode.recycle() // Recycle the scrollable node we used
    }

    // Even if performAction returns false, the scroll might have happened.
    // Returning true indicates an attempt was made on a valid scrollable node.
    return true // Report attempt was made
}

/**
 * Performs a global swipe gesture based on the direction string.
 * Requires service instance for screen dimensions and dispatching.
 *
 * @param direction "up", "down", "left", or "right".
 * @param durationMs Duration of the swipe in milliseconds.
 * @return True if the gesture was dispatched successfully, false otherwise.
 */
fun performGlobalSwipe(service: AccessibilityService, direction: String, durationMs: Long = 300): Boolean {
    val screenWidth = getScreenWidth()
    val screenHeight = getScreenHeight()
    if (screenWidth <= 0 || screenHeight <= 0) {
        Log.e(SERVICE_TAG, "Cannot perform global swipe: Invalid screen dimensions ($screenWidth x $screenHeight)")
        return false
    }

    val startX: Float
    val startY: Float
    val endX: Float
    val endY: Float
    val midX = screenWidth / 2f
    val midY = screenHeight / 2f // Use midY for horizontal swipes too
    // Use margins to avoid swiping exactly from edges/status bar/navigation bar
    val horizontalMargin = screenWidth * 0.1f // 10% margin
    val verticalMargin = screenHeight * 0.2f // 20% margin
    val topMarginForDownSwipe = screenHeight * 0.3f // Start lower for DOWN swipe
    val bottomMarginForUpSwipe = screenHeight * 0.8f // Start near bottom for UP swipe

    when (direction.lowercase()) {
        "up" -> { // Swipe Finger UP (Scrolls view DOWN)
            startX = midX
            startY = bottomMarginForUpSwipe
            endX = midX
            endY = verticalMargin
        }
        "down" -> { // Swipe Finger DOWN (Scrolls view UP)
            startX = midX
            startY = topMarginForDownSwipe
            endX = midX
            endY = screenHeight - verticalMargin
        }
        "left" -> { // Swipe Finger LEFT (Scrolls view RIGHT)
            startX = screenWidth - horizontalMargin
            startY = midY // Use midY
            endX = horizontalMargin
            endY = midY // Use midY
        }
        "right" -> { // Swipe Finger RIGHT (Scrolls view LEFT)
            startX = horizontalMargin
            startY = midY // Use midY
            endX = screenWidth - horizontalMargin
            endY = midY // Use midY
        }
        else -> {
            Log.e(SERVICE_TAG, "Invalid direction for global swipe: $direction")
            return false
        }
    }

    Log.d(SERVICE_TAG, "Performing global swipe $direction from ($startX, $startY) to ($endX, $endY)")

    val path = Path().apply {
        moveTo(startX, startY)
        lineTo(endX, endY)
    }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        .build()

    // Dispatch synchronously and return the dispatch result
    val dispatched = service.dispatchGesture(gesture, null, null)
    Log.d(SERVICE_TAG, "dispatchGesture called for global swipe $direction, synchronous result: $dispatched")
    return dispatched
}


/**
 * Perform swipe gesture using coordinates. Suspending function.
 * Returns true if the gesture dispatch was successful and the gesture completed, false otherwise.
 */
suspend fun performSwipe(service: AccessibilityService, startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean =
    suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(SERVICE_TAG, "Swipe gesture from ($startX,$startY) to ($endX,$endY) completed.")
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(SERVICE_TAG, "Swipe gesture from ($startX,$startY) to ($endX,$endY) cancelled.")
                if (continuation.isActive) continuation.resume(false)
            }
        }
        // Dispatch the gesture
        val dispatched = service.dispatchGesture(gesture, callback, null)
        Log.d(SERVICE_TAG, "DispatchGesture called for swipe, synchronous result: $dispatched")
        // If dispatchGesture itself fails immediately, resume with false
        if (!dispatched && continuation.isActive) {
            Log.e(SERVICE_TAG, "dispatchGesture failed synchronously for swipe.")
            continuation.resume(false)
        }
        // Otherwise, the callback will resume the coroutine later.
         continuation.invokeOnCancellation {
             Log.d(SERVICE_TAG, "performSwipe coroutine cancelled for ($startX, $startY) -> ($endX, $endY)")
         }
    }

/**
 * Type text into a specific node.
 * Requires the node to be editable and attempts to focus it first.
 */
fun typeText(node: AccessibilityNodeInfo?, text: String): Boolean {
    if (node == null) return false

    // Ensure the node is editable
    if (!node.isEditable) {
        Log.w(SERVICE_TAG, "Node is not editable: $node")
        return false
    }

    // Focus the node first (important for some apps)
    if (!node.isFocused) {
        val focusResult = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Log.d(SERVICE_TAG, "Focus action result on node: $focusResult")
        // It might be okay to continue even if focus returns false
    }

    // Set the text using ACTION_SET_TEXT
    val arguments = Bundle()
    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

    Log.d(SERVICE_TAG, "Type text action on node: $node, text: '$text', result: $result")
    return result
}

/**
 * Press the back button. Requires service instance.
 */
fun performBack(service: AccessibilityService): Boolean {
    val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    Log.d(SERVICE_TAG, "Perform global action BACK, result: $result")
    return result
}

/**
 * Press the home button. Requires service instance.
 */
fun performHome(service: AccessibilityService): Boolean {
    val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    Log.d(SERVICE_TAG, "Perform global action HOME, result: $result")
    return result
}

/**
 * Open the recent apps screen. Requires service instance.
 */
fun performRecents(service: AccessibilityService): Boolean {
    val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    Log.d(SERVICE_TAG, "Perform global action RECENTS, result: $result")
    return result
}

/**
 * Performs a tap gesture at the center of the element corresponding to the given index
 * from the most recently generated indexed dump (using `indexedNodeMap`).
 * Requires service instance and the map of indexed nodes.
 *
 * @param index The 0-based index of the element from the last indexed dump.
 * @param indexedNodeMap The map holding node references from the last dump.
 * @return True if the tap was dispatched successfully and completed, false otherwise.
 */
suspend fun performTapByIndex(service: AccessibilityService, index: Int, indexedNodeMap: Map<Int, AccessibilityNodeInfo>): Boolean {
    val targetNodeCopy: AccessibilityNodeInfo? // We get a copy from the map
    synchronized(indexedNodeMap) { // Access map safely
        // Get the node reference from the map - this should be a copy made during indexing
        val storedNode = indexedNodeMap[index]
        // Obtain another copy for performing the action, leave the stored one intact
        targetNodeCopy = if (storedNode != null) {
            try {
                AccessibilityNodeInfo.obtain(storedNode)
            } catch (e: IllegalStateException) {
                Log.e(SERVICE_TAG, "Node stored in map for index $index was already recycled.", e)
                null
            }
        } else {
            null
        }
    }

    if (targetNodeCopy == null) {
        Log.e(SERVICE_TAG, "performTapByIndex: No valid node found in map for index $index. UI might have changed or index is invalid.")
        return false
    }

    // Use the obtained copy (targetNodeCopy) for bounds checking and action
    try {
        val nodeBounds = Rect()
        targetNodeCopy.getBoundsInScreen(nodeBounds)

        if (nodeBounds.isEmpty) {
            val isStillVisible = targetNodeCopy.isVisibleToUser
            Log.e(SERVICE_TAG, "performTapByIndex: Node copy for index $index has empty bounds. IsVisible=$isStillVisible. Cannot tap.")
            return false // Return false, do not proceed with tap
        }

        // Calculate center coordinates
        val tapX = nodeBounds.centerX().toFloat()
        val tapY = nodeBounds.centerY().toFloat()

        // Check if coordinates are on screen
        if (tapX < 0 || tapY < 0 || tapX > getScreenWidth() || tapY > getScreenHeight()) {
            Log.w(SERVICE_TAG, "performTapByIndex: Calculated tap coordinates ($tapX, $tapY) for index $index are off-screen. Node bounds: $nodeBounds")
            return false // Cannot perform gesture tap off-screen
        }

        Log.d(SERVICE_TAG, "Performing tap by index $index at coordinates ($tapX, $tapY) for node: ${targetNodeCopy.viewIdResourceName ?: targetNodeCopy.text}")

        // Use the suspending click function which handles the gesture callback
        return clickAtSuspend(service, tapX, tapY)

    } catch (e: Exception) {
        Log.e(SERVICE_TAG, "Exception during performTapByIndex for index $index", e)
        return false
    } finally {
        // Recycle the copy we obtained specifically for this tap action
        try { targetNodeCopy.recycle() } catch (re: IllegalStateException) { /* ignore */ }
    }
}

/**
 * Performs a tap gesture at specific coordinates.
 * Requires service instance for dispatching.
 * @return True if the tap gesture was dispatched, false otherwise. Completion is async.
 */
fun performTapCoordinates(service: AccessibilityService, x: Int, y: Int): Boolean {
    if (x < 0 || y < 0 || x > getScreenWidth() || y > getScreenHeight()) { // Basic bounds check
        Log.e(SERVICE_TAG, "Invalid coordinates for tap: ($x, $y). Screen is ${getScreenWidth()}x${getScreenHeight()}")
        return false
    }
    Log.d(SERVICE_TAG, "Performing tap at coordinates: ($x, $y)")

    val gesturePath = Path()
    gesturePath.moveTo(x.toFloat(), y.toFloat())

    val gestureBuilder = GestureDescription.Builder()
    // Taps are short duration strokes (e.g., 50ms)
    gestureBuilder.addStroke(GestureDescription.StrokeDescription(gesturePath, 0, 50))

    val dispatched = service.dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            super.onCompleted(gestureDescription)
            Log.d(SERVICE_TAG, "Coordinate tap gesture completed at ($x, $y).")
        }
        override fun onCancelled(gestureDescription: GestureDescription?) {
            super.onCancelled(gestureDescription)
            Log.w(SERVICE_TAG, "Coordinate tap gesture cancelled at ($x, $y).")
        }
    }, null) // Use main handler if needed

    Log.d(SERVICE_TAG, "Coordinate tap dispatch initiated: $dispatched")
    // Return the immediate dispatch result. Note that completion is asynchronous.
    return dispatched
}


// --- Screen Info Helpers ---

/**
 * Gets the current screen width in pixels.
 * Returns 0 if unable to get metrics.
 */
fun getScreenWidth(): Int {
    val (width, _) = DeviceMetricsHolder.getMetrics()
    if (width <= 0) {
        Log.e(SERVICE_TAG, "getScreenWidth called but DeviceMetricsHolder has invalid width: $width")
        // Optionally, try a fallback like Resources.getSystem().displayMetrics.widthPixels here?
        // For now, just return the potentially invalid value or 0.
    }
    return width
}

/**
 * Gets the current screen height in pixels.
 * Returns 0 if unable to get metrics.
 */
fun getScreenHeight(): Int {
    val (_, height) = DeviceMetricsHolder.getMetrics()
     if (height <= 0) {
        Log.e(SERVICE_TAG, "getScreenHeight called but DeviceMetricsHolder has invalid height: $height")
        // Optionally, try a fallback like Resources.getSystem().displayMetrics.heightPixels here?
    }
    return height
}
