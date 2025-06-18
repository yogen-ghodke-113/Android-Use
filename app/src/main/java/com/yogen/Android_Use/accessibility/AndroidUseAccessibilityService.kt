package com.yogen.Android_Use.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
// Import for the helper functions
import com.yogen.Android_Use.accessibility.service_files.getDetailedDisplayMetrics
import com.yogen.Android_Use.accessibility.service_files.findNodesByText
import com.yogen.Android_Use.accessibility.service_files.getAllClickableNodes
import com.yogen.Android_Use.accessibility.service_files.getAllInteractiveNodes
import com.yogen.Android_Use.accessibility.service_files.getAllNodes
import com.yogen.Android_Use.accessibility.service_files.getNodeIdentifier
import com.yogen.Android_Use.api.ApiClient
import com.yogen.Android_Use.models.Selector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min
import com.yogen.Android_Use.utils.DeviceMetricsHolder
import com.yogen.Android_Use.accessibility.OverlayManager
import com.yogen.Android_Use.R
// Removing the 3 missing imports:
// import com.yogen.Android_Use.accessibility.service_files.AccessibilityActionPerformer
// import com.yogen.Android_Use.accessibility.service_files.AccessibilityDataRetriever
// import com.yogen.Android_Use.accessibility.service_files.AccessibilityNodeInfoUtils // Kept for potential use
import com.yogen.Android_Use.execution.ActionResult // Added import
import com.yogen.Android_Use.execution.engine_files.EngineConstants
import com.yogen.Android_Use.models.NodePayload // Keep if still needed internally
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class AndroidUseAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Add OverlayManager instance
    private lateinit var overlayManager: OverlayManager

    // --- LRU Cache ---
    private val CACHE_MAX_SIZE = 32
    private val nodeCache: MutableMap<String, AccessibilityNodeInfo> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, AccessibilityNodeInfo>(CACHE_MAX_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AccessibilityNodeInfo>): Boolean {
                    val shouldRemove = size > CACHE_MAX_SIZE
                    if (shouldRemove) {
                        // Log.v(TAG, "LRU Cache limit ($CACHE_MAX_SIZE) reached. Evicting node: ${eldest.key}")
                        try { eldest.value?.recycle() } catch (e: Exception) { /* Log optional */ }
                    }
                    return shouldRemove
                }
            }
        )

    companion object {
        const val TAG = "AndroidUseAccService"
        @Volatile var instance: AndroidUseAccessibilityService? = null
            private set
        const val ACTION_ACCESSIBILITY_CONNECTED = "com.yogen.Android_Use.ACCESSIBILITY_CONNECTED"
        const val ACTION_ACCESSIBILITY_DISCONNECTED = "com.yogen.Android_Use.ACCESSIBILITY_DISCONNECTED"
    }

    // +++ NEW Public Handler Method +++
    // Explicitly public for clarity, though default in Kotlin
    public fun handleNodeRequest(action: String?, correlationId: String?, extras: Bundle?) {
        if (action == null) {
            Log.e(TAG, "handleNodeRequest called with null action")
            return
        }
        Log.d(TAG, "Direct call received for action: $action, CorrId: $correlationId")

        // Reuse the coroutine logic from the old receiver
        scope.launch { // Keep IO scope for the overall handling
            var success = false
            var message = "Unknown error in handler."
            var selectors: List<Selector>? = null
            var nodesToCache: List<AccessibilityNodeInfo>? = null

            try {
                // Switch to Default dispatcher for potentially long node retrieval
                val resultPair: Pair<List<Selector>, List<AccessibilityNodeInfo>>? =
                    withContext(Dispatchers.Default) {
                        when (action) {
                            // Use ACTION strings defined in ApiClient for consistency
                            ApiClient.ACTION_REQUEST_NODES_BY_TEXT -> {
                                val query = extras?.getString(ApiClient.EXTRA_TEXT_QUERY)
                                if (query != null) findNodesByText(this@AndroidUseAccessibilityService, query)
                                else { message = "Missing text query."; null }
                            }
                            ApiClient.ACTION_REQUEST_INTERACTIVE_NODES -> getAllInteractiveNodes(this@AndroidUseAccessibilityService)
                            ApiClient.ACTION_REQUEST_ALL_NODES -> getAllNodes(this@AndroidUseAccessibilityService)
                            ApiClient.ACTION_REQUEST_CLICKABLE_NODES -> getAllClickableNodes(this@AndroidUseAccessibilityService)
                            ApiClient.ACTION_REQUEST_LIST_PACKAGES -> { message = "Package listing not implemented."; null }
                            else -> { message = "Unknown request action: $action"; Log.w(TAG, message); null }
                        }
                    } // End of withContext(Dispatchers.Default)

                if (resultPair != null) {
                    selectors = resultPair.first
                    nodesToCache = resultPair.second
                    success = true
                    message = "Retrieved nodes successfully for action: $action."
                    Log.d(TAG, "Node retrieval success via direct call for $action (CorrId: $correlationId)")
                } else if (action != ApiClient.ACTION_REQUEST_LIST_PACKAGES) {
                     Log.e(TAG, "Failed node retrieval via direct call for $action (CorrId: $correlationId): $message")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing node request '$action' via direct call (CorrId: $correlationId): ${e.message}", e)
                message = "Error during node retrieval: ${e.message}"
                success = false
            } finally {
                nodesToCache?.let { cacheNodes(it) }

                val apiClient = ApiClient.getInstance(applicationContext)
                if (action == ApiClient.ACTION_REQUEST_LIST_PACKAGES) {
                    // apiClient.sendPackagesResult(...) // Assuming similar logic for package result
                } else {
                    // Map the ACTION string back to the appropriate RESULT type string for ApiClient
                    val resultType = when (action) {
                        ApiClient.ACTION_REQUEST_NODES_BY_TEXT -> ApiClient.TYPE_NODES_BY_TEXT_RESULT
                        ApiClient.ACTION_REQUEST_INTERACTIVE_NODES -> ApiClient.TYPE_INTERACTIVE_NODES_RESULT
                        ApiClient.ACTION_REQUEST_ALL_NODES -> ApiClient.TYPE_ALL_NODES_RESULT
                        ApiClient.ACTION_REQUEST_CLICKABLE_NODES -> ApiClient.TYPE_CLICKABLE_NODES_RESULT
                        else -> "unknown_node_result"
                    }
                    Log.d(TAG, "Sending node result from direct call for $action (CorrId: $correlationId), Success: $success, ResultType: $resultType")
                    // Pass the CORRECTLY MAPPED resultType string
                    apiClient.sendNodeListResult(resultType, selectors, correlationId, success, message)
                }
            }
        }
    }
    // +++ END Public Handler Method +++

    // --- Service Lifecycle ---
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        configureServiceInfo()
        notifyConnected()

        // Initialize overlay manager. It will use its own default offsets.
        overlayManager = OverlayManager(this)

        Log.i(TAG, "Accessibility service connected and configured.")
    }

    private fun configureServiceInfo() {
        try {
            // Create config via separate function to avoid val reassignment issues
            val serviceConfig = createServiceConfig()
            
            // Apply configuration
            setServiceInfo(serviceConfig)
            Log.i(TAG, "Service flags and capabilities set successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring service info", e)
        }
    }
    
    private fun createServiceConfig(): AccessibilityServiceInfo {
        val config = AccessibilityServiceInfo()
        
        // Configure event types
        config.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        config.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        config.notificationTimeout = 100
        
        // Set flags one by one instead of chaining operations
        var allFlags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        allFlags = allFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        allFlags = allFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        // FLAG_REQUEST_TOUCH_EXPLORATION_MODE might interfere with normal interaction, remove if causing issues
        // allFlags = allFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE 
        allFlags = allFlags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        
        // Add the flags directly
        config.flags = allFlags

        // For API 24+ (Nougat), we can perform gestures
        // But we don't need to set capabilities field directly
        // Android will handle this based on the flags we set
        
        // REMOVED direct capabilities setting:
        // var allCaps = 0
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        //     allCaps = allCaps or AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
        // }
        // config.capabilities = allCaps 
        
        return config
    }

    private fun notifyConnected() {
        val intent = Intent(ACTION_ACCESSIBILITY_CONNECTED).setPackage(packageName)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.i(TAG, "Sent $ACTION_ACCESSIBILITY_CONNECTED broadcast (for activity)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Log.d(TAG, "Significant UI Change Event: ${AccessibilityEvent.eventTypeToString(eventType)}. Clearing cache.")
            clearAndRecycleCache("UI Change Event: ${AccessibilityEvent.eventTypeToString(eventType)}")
        }
    }

    override fun onInterrupt() { cleanupService() }
    override fun onDestroy() { 
        super.onDestroy()
        
        // Clean up overlay before service destruction
        if (::overlayManager.isInitialized) {
            overlayManager.hideOverlay()
        }
        
        cleanupService() 
    }

    private fun cleanupService() {
        Log.i(TAG, "Cleaning up Accessibility Service...")
        instance = null
        clearAndRecycleCache("Service Cleanup")
        val intent = Intent(ACTION_ACCESSIBILITY_DISCONNECTED).setPackage(packageName)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.i(TAG, "Service cleaned up.")
    }

    private fun clearAndRecycleCache(reason: String) {
         // Fix: Create a copy of nodes to recycle to avoid concurrent modification
         val nodesToRecycle = synchronized(nodeCache) {
            val nodes = nodeCache.values.toList()
            nodeCache.clear()
            nodes
         }
         // Launch background job to recycle nodes
         scope.launch(Dispatchers.IO) { // Use IO dispatcher for recycling
             var count = 0
             val startTime = System.currentTimeMillis()
             nodesToRecycle.forEach { node ->
                  try { node?.recycle(); count++ }
                  catch (e: Exception) { /* Log recycling error if needed */ }
              }
              val duration = System.currentTimeMillis() - startTime
              // Log details off the main thread
              // Log.d(TAG, "Cleared cache ($reason), recycled $count nodes off-thread in ${duration}ms.")
         }
         // Main thread returns immediately
         // Log.d(TAG, "Initiated background cache clearing ($reason).")
    }

    // --- Utility Functions ---
    private fun refreshNodeIfNeeded(node: AccessibilityNodeInfo?): Boolean {
        try { return node?.refresh() ?: false } catch (e: Exception) { return false }
    }

    // Define getRootSafely
    private fun getRootSafely(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow?.let { root ->
                 val rootCopy = AccessibilityNodeInfo.obtain(root)
                 if(refreshNodeIfNeeded(rootCopy)) rootCopy else { rootCopy?.recycle(); null }
            }
        } catch (e: Exception) { null }
    }

    // --- Selector Resolution Logic ---
    private fun calculateIoU(rect1: Rect?, rect2: Rect?): Float {
        if (rect1 == null || rect2 == null || rect1.isEmpty || rect2.isEmpty) return 0.0f
        val left = max(rect1.left, rect2.left) // Use imported kotlin.math.max
        val top = max(rect1.top, rect2.top)
        val right = min(rect1.right, rect2.right) // Use imported kotlin.math.min
        val bottom = min(rect1.bottom, rect2.bottom)
        if (right < left || bottom < top) return 0.0f
        val intersectionArea = (right - left).toFloat() * (bottom - top).toFloat()
        val area1 = (rect1.right - rect1.left).toFloat() * (rect1.bottom - rect1.top).toFloat()
        val area2 = (rect2.right - rect2.left).toFloat() * (rect2.bottom - rect2.top).toFloat()
        val unionArea = area1 + area2 - intersectionArea // Correct float subtraction
        return if (unionArea > 0f) intersectionArea / unionArea else 0.0f
    }

    private fun checkBoundsOverlap(node: AccessibilityNodeInfo, selector: Selector, threshold: Float = 0.7f): Boolean {
        val originalBounds = selector.bounds ?: return true
        val currentBounds = Rect()
        return try {
            node.getBoundsInScreen(currentBounds)
            !currentBounds.isEmpty && (calculateIoU(originalBounds, currentBounds) >= threshold)
        } catch (e: Exception) { false }
    }

    // This is a helper extension function to traverse node hierarchies safely
    private fun AccessibilityNodeInfo.walk(action: (AccessibilityNodeInfo?) -> Unit) {
        val stack = mutableListOf<AccessibilityNodeInfo>()
        stack.add(this)
        
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            try {
                action(current)
                
                // Add all children to the stack
                for (i in 0 until current.childCount) {
                    try {
                        val child = current.getChild(i)
                        if (child != null) {
                            stack.add(child)
                        }
                    } catch (e: Exception) {
                        // Ignore individual child access errors
                    }
                }
            } catch (e: Exception) {
                // Log error and continue with next node
                Log.e(TAG, "Error walking node: ${e.message}")
            }
        }
    }

    private fun findMatchingNode(selector: Selector): AccessibilityNodeInfo? {
        val root = getRootSafely() ?: return null // Gets a copy or null
        var bestMatch: AccessibilityNodeInfo? = null
        var highestScore = -1
        try {
            // Explicitly type lambda parameter and handle nulls
            root.walk { node: AccessibilityNodeInfo? ->
                if (node == null) return@walk
                var nodeCopy: AccessibilityNodeInfo? = null
                try {
                    // Use obtain(node)
                    nodeCopy = AccessibilityNodeInfo.obtain(node) ?: return@walk
                    if (!refreshNodeIfNeeded(nodeCopy)) return@walk

                    var currentScore = 0
                    var isPotentialMatch = true
                    if (nodeCopy.windowId != selector.windowId) isPotentialMatch = false

                    if (isPotentialMatch && selector.viewId != null) {
                        if (nodeCopy.viewIdResourceName == selector.viewId) currentScore += 10 else isPotentialMatch = false
                    }
                    if (isPotentialMatch && selector.className != null) {
                        if (nodeCopy.className?.toString() == selector.className) currentScore += 5 else isPotentialMatch = false
                    }
                    if (isPotentialMatch && selector.text != null) {
                        if ((nodeCopy.text?.toString() ?: "") == selector.text) currentScore += 3 else isPotentialMatch = false
                    }
                    if (isPotentialMatch && selector.contentDesc != null) {
                        if ((nodeCopy.contentDescription?.toString() ?: "") == selector.contentDesc) currentScore += 3 else isPotentialMatch = false
                    }
                    if (isPotentialMatch) {
                        var actionabilityMatch = true
                        if (selector.isClickable && !nodeCopy.isClickable) actionabilityMatch = false
                        if (selector.isEditable && !nodeCopy.isEditable) actionabilityMatch = false
                        if (selector.isLongClickable && !nodeCopy.isLongClickable) actionabilityMatch = false
                        
                        if (!actionabilityMatch) isPotentialMatch = false else currentScore += 1
                    }

                    if (isPotentialMatch && currentScore > highestScore) {
                        highestScore = currentScore
                        bestMatch?.recycle()
                        bestMatch = nodeCopy // Keep the copy
                        nodeCopy = null // Prevent finally block from recycling the new bestMatch
                    }
                } catch (e: Exception) { /* Log optional */ }
                finally { nodeCopy?.recycle() } // Recycle if not kept
            }
        } finally { root.recycle() }
        return bestMatch // Caller must recycle
    }

     private fun resolveNodeFromSelector(selector: Selector, checkActionability: Boolean = true): AccessibilityNodeInfo? {
         var resolvedNode: AccessibilityNodeInfo? = null
         var obtainedFromCache = false
         var resolutionPath = "None"
         val cachedNodeRef = getCachedNode(selector)
         if (cachedNodeRef != null) {
             val cachedNodeCopy = AccessibilityNodeInfo.obtain(cachedNodeRef)
             if (cachedNodeCopy != null) {
                 try {
                     if (refreshNodeIfNeeded(cachedNodeCopy) && cachedNodeCopy.windowId == selector.windowId &&
                         (!checkActionability || checkNodeActionability(cachedNodeCopy, selector)) &&
                         checkBoundsOverlap(cachedNodeCopy, selector)) {
                             resolvedNode = cachedNodeCopy
                             obtainedFromCache = true
                             resolutionPath = "Cache"
                             Log.d(TAG, "Resolved node from cache: ${getNodeIdentifier(resolvedNode)}")
                     } else {
                          val key = generateCacheKey(cachedNodeCopy)
                          if (key != null) { synchronized(nodeCache) { nodeCache.remove(key) }?.recycle() }
                          Log.d(TAG, "Cache entry invalid or stale, removed: ${key ?: "(no key)"}")
                     }
                 } catch (e: Exception) { Log.e(TAG, "Error checking cached node", e) }
                 finally { if (!obtainedFromCache) cachedNodeCopy.recycle() }
             }
         }
         if (resolvedNode == null) {
             Log.d(TAG, "Node not found in cache or cache invalid. Searching tree via primary identifiers...")
             val foundNode = findMatchingNode(selector)
             if (foundNode != null) {
                  Log.d(TAG, "Found potential match via primary identifiers: ${getNodeIdentifier(foundNode)}")
                 // --- START: DETAILED LOGGING ADDED ---
                 val isActionable = !checkActionability || checkNodeActionability(foundNode, selector)
                 val boundsOverlap = checkBoundsOverlap(foundNode, selector, threshold = 0.5f)
                 Log.d(TAG, "Primary check: isActionable=$isActionable, boundsOverlap=$boundsOverlap (threshold=0.5)")
                 // --- END: DETAILED LOGGING ADDED ---
                 if (isActionable && boundsOverlap) { // Use the logged variables
                         resolvedNode = foundNode
                         resolutionPath = "Primary Find"
                         Log.d(TAG, "Resolved node via primary identifiers: ${getNodeIdentifier(resolvedNode)}")
                 } else {
                     Log.d(TAG, "Primary match failed actionability/bounds check.")
                     foundNode.recycle()
                 }
             }
             // --- START: Bounds Fallback --- 
             if (resolvedNode == null && selector.bounds != null && selector.className != null) {
                 Log.d(TAG, "Primary resolution failed. Attempting fallback using bounds and class...")
                 val fallbackNode = findNodeByBoundsAndClass(selector.bounds, selector.className)
                 if (fallbackNode != null) {
                     Log.d(TAG, "Found potential match via bounds fallback: ${getNodeIdentifier(fallbackNode)}")
                      if ((!checkActionability || checkNodeActionability(fallbackNode, selector)) && checkBoundsOverlap(fallbackNode, selector, threshold = 0.7f)) { // Stricter overlap for fallback?
                          resolvedNode = fallbackNode
                          resolutionPath = "Bounds Fallback"
                          Log.d(TAG, "Resolved node via bounds fallback: ${getNodeIdentifier(resolvedNode)}")
                      } else {
                          Log.d(TAG, "Bounds fallback match failed actionability/overlap check.")
                          fallbackNode.recycle()
                      }
                 } else {
                      Log.d(TAG, "Bounds fallback found no matching node.")
                 }
             }
            // --- END: Bounds Fallback ---
         }
         if (resolvedNode == null) {
             Log.w(TAG, "Failed to resolve selector via any method: $selector")
         } else {
             Log.i(TAG, "Successfully resolved selector via [${resolutionPath}]: ${getNodeIdentifier(resolvedNode)}")
         }
         // Cache the newly resolved node if found outside cache
         if (resolvedNode != null && !obtainedFromCache) {
             cacheNodes(listOf(AccessibilityNodeInfo.obtain(resolvedNode))) // Cache a copy
         }
         return resolvedNode // Caller MUST recycle this node
     }

    // --- START: New Helper for Bounds Fallback ---
    private fun findNodeByBoundsAndClass(targetBounds: Rect, targetClassName: String): AccessibilityNodeInfo? {
        val root = getRootSafely() ?: return null
        var bestMatch: AccessibilityNodeInfo? = null
        var bestIoU = 0.0f

        try {
            root.walk { node: AccessibilityNodeInfo? ->
                if (node == null) return@walk
                var nodeCopy: AccessibilityNodeInfo? = null
                try {
                    nodeCopy = AccessibilityNodeInfo.obtain(node) ?: return@walk
                    if (!refreshNodeIfNeeded(nodeCopy)) return@walk
                    
                    // Check class name first (cheaper)
                    if (nodeCopy.className?.toString() == targetClassName) {
                        val currentBounds = Rect()
                        nodeCopy.getBoundsInScreen(currentBounds)
                        if (!currentBounds.isEmpty) {
                            val iou = calculateIoU(targetBounds, currentBounds)
                            // Find node with highest IoU above a threshold (e.g., 0.7)
                            if (iou > bestIoU && iou >= 0.7f) {
                                bestIoU = iou
                                bestMatch?.recycle() // Recycle previous best match
                                bestMatch = nodeCopy // Keep the copy
                                nodeCopy = null // Prevent finally block from recycling the new best match
                            }
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "Error checking node in findNodeByBoundsAndClass", e) }
                 finally { nodeCopy?.recycle() } // Recycle if not kept
            }
        } finally {
            root.recycle()
        }
        Log.d(TAG, "findNodeByBoundsAndClass result: ${getNodeIdentifier(bestMatch)} with IoU: $bestIoU")
        return bestMatch // Caller must recycle
    }
   // --- END: New Helper for Bounds Fallback ---

    private fun checkNodeActionability(node: AccessibilityNodeInfo?, selector: Selector): Boolean {
        node ?: return false
        try {
            if (selector.isClickable && !node.isClickable) return false
            if (selector.isEditable && !node.isEditable) return false
            if (selector.isLongClickable && !node.isLongClickable) return false
            return true
        } catch (e: Exception) { return false }
    }

    // --- Selector-based Action Implementations ---
    // Implementation for tap by selector using the overlay after the action
    suspend fun performTapBySelector(selector: Selector): Boolean {
        Log.d(TAG, "performTapBySelector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
        
        // Find the node
        val node = resolveNodeFromSelector(selector)
        if (node == null) {
            Log.e(TAG, "performTapBySelector: Failed to find node matching selector")
            return false
        }
        
        try {
            // Perform the action first
            val result = performClickOnNode(node)
            
            if (result) {
                Log.d(TAG, "performTapBySelector: Successfully clicked node: ${getNodeIdentifier(node)}")
                
                // Get clickable nodes AFTER the action for visualization
                val (_, clickableNodes) = getAllClickableNodes(this)
                
                // Important: Show overlay AFTER taking screenshot for the next cycle
                // This way the overlay doesn't appear in screenshots used for reasoning
                withContext(Dispatchers.Main) {
                    overlayManager.showNodeBoundingBoxes(clickableNodes, 3000) // 3 seconds constant display
                }
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "performTapBySelector: Error", e)
            return false
        } finally {
            node.recycle()
        }
    }
    
    suspend fun performInputBySelector(selector: Selector, text: String): Boolean {
        Log.d(EngineConstants.ACCESSIBILITY_TAG, "performInputBySelector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
        
        // Find the node
        val node = resolveNodeFromSelector(selector)
        if (node == null) {
            Log.e(EngineConstants.ACCESSIBILITY_TAG, "performInputBySelector: Failed to find node matching selector")
            return false
        }
        
        var success = false // Use var for reassignment
        try {
            // Perform the action
            val bundle = Bundle().apply { putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

            // Log details after action
            if (success) {
                val textActuallySet = bundle.getString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                Log.d(EngineConstants.TAG_ACCESSIBILITY, "Text set action successful for: '$textActuallySet'") // Use constant
                // After setting text, especially in password fields, a small delay might help ensure the UI updates
                Log.d(EngineConstants.TAG_ACCESSIBILITY, "Starting 200ms delay after setting text...") // Use constant
                delay(200)
                Log.d(EngineConstants.TAG_ACCESSIBILITY, "Finished 200ms delay after setting text.") // Use constant
                // REMOVED: Erroneous reassignment to ActionResult
                // result = ActionResult(success = true, message = "Text set successfully.")
            } else {
                 Log.w(EngineConstants.ACCESSIBILITY_TAG, "performInputBySelector: ACTION_SET_TEXT failed for selector.")
            }

            return success
        } catch (e: Exception) {
            Log.e(EngineConstants.ACCESSIBILITY_TAG, "performInputBySelector: Error", e)
            return false
        } finally {
            node.recycle()
        }
    }
    
    // Helper method to show overlay after any action
    suspend fun showOverlayAfterAction() {
        try {
            // Get clickable nodes after the action
            val (_, clickableNodes) = getAllClickableNodes(this)
            
            // Show overlay on main thread
            withContext(Dispatchers.Main) {
                overlayManager.showNodeBoundingBoxes(clickableNodes, 3000) // 3 seconds constant display
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay after action: ${e.message}")
        }
    }
    
    // Implementation of global swipe that shows the overlay
    suspend fun performGlobalSwipe(direction: String): Boolean {
        Log.d(TAG, "performGlobalSwipe: direction=$direction")
        
        try {
            // Get screen dimensions
            val (screenWidth, screenHeight) = DeviceMetricsHolder.getMetrics()
            if (screenWidth <= 0 || screenHeight <= 0) {
                Log.e(TAG, "Cannot perform swipe: Invalid screen dimensions ($screenWidth x $screenHeight)")
                return false
            }
            
            // Calculate swipe coordinates
            val (startX, startY, endX, endY) = when (direction.lowercase()) {
                "up" -> listOf(
                    screenWidth / 2f,
                    screenHeight * 0.8f,
                    screenWidth / 2f,
                    screenHeight * 0.2f
                )
                "down" -> listOf(
                    screenWidth / 2f,
                    screenHeight * 0.2f,
                    screenWidth / 2f,
                    screenHeight * 0.8f
                )
                "left" -> listOf(
                    screenWidth * 0.8f,
                    screenHeight / 2f,
                    screenWidth * 0.2f,
                    screenHeight / 2f
                )
                "right" -> listOf(
                    screenWidth * 0.2f,
                    screenHeight / 2f,
                    screenWidth * 0.8f,
                    screenHeight / 2f
                )
                else -> return false
            }
            
            // Create and perform gesture
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)
            
            val gesture = GestureDescription.Builder()
                .addStroke(StrokeDescription(path, 0, 300))
                .build()
                
            val result = dispatchGesture(gesture, null, null)
            
            if (result) {
                // Reduce delay to 200ms
                delay(200)
                
                // Get clickable nodes AFTER the action
                val (_, clickableNodes) = getAllClickableNodes(this)
                
                // Show overlay AFTER taking screenshot for the next cycle
                withContext(Dispatchers.Main) {
                    overlayManager.showNodeBoundingBoxes(clickableNodes, 3000) // 3 seconds constant display
                }
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error in performGlobalSwipe: ${e.message}", e)
            return false
        }
    }

    // --- Other Service Methods ---

    // Make internal method public for ActionHandlers
    fun performGlobalActionInternal(actionId: Int): Boolean {
        return try { super.performGlobalAction(actionId) } catch (e: Exception) { false }
    }

    suspend fun performOpenAppDrawer(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (performGlobalActionInternal(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)) return true
        }
        if (openDrawerIconFallback()) return true
        if (!performGlobalActionInternal(AccessibilityService.GLOBAL_ACTION_HOME)) return false
        
        // Restore original delay of 500ms
        //delay(500)
        
        return swipeUpDrawer()
    }

    // --- Fallback Helpers ---
    // Define openDrawerIconFallback
    private fun openDrawerIconFallback(): Boolean {
        val root = getRootSafely() ?: return false
        val potentialTexts = listOf("Apps", "All apps")
        var drawerButton: AccessibilityNodeInfo? = null
        var found = false
        try {
            for (text in potentialTexts) {
                 val nodes = root.findAccessibilityNodeInfosByText(text)
                 
                 // Use a regular for loop to avoid closure issues with smart casting
                 var foundNode: AccessibilityNodeInfo? = null
                 if (nodes != null) {
                     for (node in nodes) {
                         if (node == null) continue
                         
                         var isActuallyClickable = false
                         var checkNode: AccessibilityNodeInfo? = null
                         try {
                             checkNode = AccessibilityNodeInfo.obtain(node)
                             while (checkNode != null) {
                                 if (!refreshNodeIfNeeded(checkNode)) break
                                 if (checkNode.isClickable) { 
                                     isActuallyClickable = true
                                     break 
                                 }
                                 val parent = checkNode.parent
                                 checkNode.recycle()
                                 checkNode = parent
                             }
                         } finally { 
                             checkNode?.recycle() 
                         }
                         
                         if (isActuallyClickable) {
                             foundNode = node
                             break
                         }
                     }
                     
                     // Recycle all nodes we're not keeping
                     for (node in nodes) {
                         if (node != foundNode) {
                             node?.recycle()
                         }
                     }
                 }
                 
                 if (foundNode != null) {
                     // Create a copy to keep
                     drawerButton = AccessibilityNodeInfo.obtain(foundNode)
                     foundNode.recycle()
                     found = true
                     break
                 }
            }
        } catch (e: Exception) { /* Log */ } finally { root.recycle() }
        
        return if (found && drawerButton != null) {
            val result = drawerButton.safeClick()
            drawerButton.recycle()
            result
        } else {
            drawerButton?.recycle()
            false 
        }
    }

    private fun swipeUpDrawer(): Boolean {
        // Check for gesture capability without using serviceInfo property directly
        var canPerformGestures = false
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val info = super.getServiceInfo()
            if (info != null) {
                val caps = info.capabilities
                canPerformGestures = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
            }
        }
        
        if (!canPerformGestures) {
            return false
        }
        
        // Get screen dimensions
        val (width, height) = DeviceMetricsHolder.getMetrics()
        // val metrics = getDetailedDisplayMetrics(this)
        // if (metrics == null) {
        //     Log.e(TAG, "swipeUpDrawer: Failed to get display metrics")
        //     return false
        // }
        // val width = metrics.widthPixels
        // val height = metrics.heightPixels
        
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "swipeUpDrawer: Invalid screen dimensions from DeviceMetricsHolder ($width x $height)")
            return false
        }
        
        // Calculate swipe coordinates
        val startX = width / 2f
        val startY = height * 0.90f
        val endX = width / 2f
        val endY = height * 0.25f
        val duration = 200L
        
        // Create and perform gesture
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        
        val stroke = StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        val gesture = builder.build()
        
        return dispatchGesture(gesture, null, null)
    }

    // safeClick helper
    private fun AccessibilityNodeInfo.safeClick(): Boolean {
        var nodeToClick: AccessibilityNodeInfo? = null
        var success = false
        var currentNode: AccessibilityNodeInfo? = null
        try {
             // Fix: Use obtain(this)
             currentNode = AccessibilityNodeInfo.obtain(this)
             while (currentNode != null) {
                 if (!refreshNodeIfNeeded(currentNode)) break
                 if (currentNode.isClickable) {
                     nodeToClick = currentNode
                     currentNode = null
                     break
                 }
                 val parent = currentNode.parent
                 currentNode.recycle()
                 currentNode = parent
             }
             currentNode?.recycle()
             if (nodeToClick != null) { success = nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        } catch (e: Exception) { /* Log */ }
        finally { nodeToClick?.recycle() }
        return success
    }

    // --- Node Caching Logic ---
    private fun generateCacheKey(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        return try {
            val viewId = node.viewIdResourceName
            val windowId = node.windowId
            if (!viewId.isNullOrEmpty()) "$windowId:$viewId" else "$windowId:${node.hashCode()}"
        } catch (e: Exception) { null }
    }

     private fun cacheNodes(nodes: List<AccessibilityNodeInfo>) {
        var addedCount = 0
        
        for (originalNode in nodes) {
            if (originalNode == null) continue
            
            try {
                // First obtain a copy from the original node
                val nodeCopy = AccessibilityNodeInfo.obtain(originalNode)
                if (nodeCopy == null) {
                    continue
                }
                
                // Generate key from the copy
                val nodeKey = generateCacheKey(nodeCopy)
                if (nodeKey == null) {
                    nodeCopy.recycle()
                    continue
                }
                
                // Add to cache and handle any evicted node outside
                var evictedNode: AccessibilityNodeInfo? = null
                
                synchronized(nodeCache) { 
                    evictedNode = nodeCache.put(nodeKey, nodeCopy)
                }
                
                // Recycle the evicted node if it exists and is different
                if (evictedNode != null && evictedNode != nodeCopy) {
                    evictedNode?.recycle()
                }
                
                addedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Error caching node: ${e.message}")
            } finally {
                // Always recycle the original node
                try {
                    originalNode.recycle()
                } catch (e: Exception) {
                    // Ignore recycling errors
                }
            }
        }
        
        if (addedCount > 0) {
            // Log.d(TAG, "Added $addedCount nodes to cache")
        }
    }

    private fun getCachedNode(selector: Selector): AccessibilityNodeInfo? {
        val key = if (!selector.viewId.isNullOrEmpty()) {
            "${selector.windowId}:${selector.viewId}"
        } else { return null }
        synchronized(nodeCache) { return nodeCache[key] }
    }
    // --- END Node Caching Logic ---

    // Add these methods for handling node clicks and other actions
    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click: ${e.message}")
            false
        }
    }
    
    /**
     * Adjusts the calibration of overlay bounding boxes.
     * @param xOffset X-axis offset in pixels. Positive shifts right, negative shifts left.
     * @param yOffset Y-axis offset in pixels. Negative shifts up, positive shifts down.
     */
    fun adjustOverlayCalibration(xOffset: Int, yOffset: Int) {
        if (::overlayManager.isInitialized) {
            Log.i(TAG, "Adjusting overlay calibration to: x=$xOffset, y=$yOffset")
            overlayManager.setCalibrationOffsets(xOffset, yOffset)
            Log.i(TAG, "Overlay calibration adjusted: x=$xOffset, y=$yOffset")
        } else {
            Log.e(TAG, "Cannot adjust overlay calibration - overlayManager not initialized")
        }
    }
    
    suspend fun performCopyBySelector(selector: Selector): Boolean {
        Log.d(TAG, "performCopyBySelector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
        
        // Find the node
        val node = resolveNodeFromSelector(selector)
        if (node == null) {
            Log.e(TAG, "performCopyBySelector: Failed to find node matching selector")
            return false
        }
        
        try {
            // Perform the action
            val result = node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            
            // Show overlay after action if successful
            if (result) {
                // showOverlayAfterAction() // <-- Commented out for testing
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "performCopyBySelector: Error", e)
            return false
        } finally {
            node.recycle()
        }
    }
    
    suspend fun performPasteBySelector(selector: Selector): Boolean {
        Log.d(TAG, "performPasteBySelector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
        
        // Find the node
        val node = resolveNodeFromSelector(selector)
        if (node == null) {
            Log.e(TAG, "performPasteBySelector: Failed to find node matching selector")
            return false
        }
        
        try {
            // Perform the action
            val result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            
            // Show overlay after action if successful
            if (result) {
                // showOverlayAfterAction() // <-- Commented out for testing
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "performPasteBySelector: Error", e)
            return false
        } finally {
            node.recycle()
        }
    }
    
    suspend fun performSelectBySelector(selector: Selector, start: Int?, end: Int?): Boolean {
        Log.d(TAG, "performSelectBySelector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
        
        // Find the node
        val node = resolveNodeFromSelector(selector)
        if (node == null) {
            Log.e(TAG, "performSelectBySelector: Failed to find node matching selector")
            return false
        }
        
        try {
            // Prepare selection arguments
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start ?: 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end ?: (node.text?.length ?: 0))
            }
            
            // Perform the action
            val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
            
            // Show overlay after action if successful
            if (result) {
                // showOverlayAfterAction() // <-- Commented out for testing
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "performSelectBySelector: Error", e)
            return false
        } finally {
            node.recycle()
        }
    }
    
    suspend fun performLongClickBySelector(selector: Selector): Boolean {
        Log.d(TAG, "performLongClickBySelector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
        
        // Find the node
        val node = resolveNodeFromSelector(selector)
        if (node == null) {
            Log.e(TAG, "performLongClickBySelector: Failed to find node matching selector")
            return false
        }
        
        try {
            // Perform the action
            val result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            
            // Show overlay after action if successful
            if (result) {
                // showOverlayAfterAction() // <-- Commented out for testing
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "performLongClickBySelector: Error", e)
            return false
        } finally {
            node.recycle()
        }
    }

} // End of AndroidUseAccessibilityService class