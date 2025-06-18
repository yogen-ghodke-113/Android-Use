@file:Suppress("VAL_REASSIGNMENT") // Keep if needed for specific handlers

package com.yogen.Android_Use.execution.engine_files

// --- Imports --- //
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK // Import constants directly
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo // Keep for specific actions if needed
import com.google.gson.Gson
import com.yogen.Android_Use.accessibility.AndroidUseAccessibilityService
import com.yogen.Android_Use.api.ApiClient
import com.yogen.Android_Use.execution.ActionResult
import com.yogen.Android_Use.execution.ExecutionEngine
import com.yogen.Android_Use.execution.engine_files.EngineConstants
import com.yogen.Android_Use.execution.engine_files.getNodeIdentifier
import com.yogen.Android_Use.models.Selector // Import Selector
import com.yogen.Android_Use.utils.DeviceControlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
// --- End Imports --- //


// --- Type Aliases --- //
// Original handler type for actions taking JSONObject params
typealias JsonActionHandler = suspend (service: AndroidUseAccessibilityService?, context: Context, params: JSONObject) -> Pair<Boolean, String>

// New handler type for actions taking a Selector object
typealias SelectorActionHandler = suspend (service: AndroidUseAccessibilityService?, context: Context, selector: Selector) -> Pair<Boolean, String>

// Wrapper type to allow mixed handlers in the map (we'll resolve this later)
// For now, keep the original type alias, and parse inside the ExecutionEngine
typealias ActionHandler = suspend (service: AndroidUseAccessibilityService?, context: Context, params: JSONObject) -> Pair<Boolean, String>
// --- End Type Aliases --- //


// --- Action Handlers --- //

// REMOVED: handleTypeText

// REMOVED: handleScroll

// handlePerformGlobalAction: Needs service, params (JSONObject)
suspend fun handlePerformGlobalAction(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    service ?: return Pair(false, "AccessibilityService unavailable for handlePerformGlobalAction.")

    // Extract the action ID
    val actionIdString = params.optString("action_id") // Use string directly instead of PARAM_ACTION_ID
    
    // Map action ID string to Android's AccessibilityService action constants
    val actionId = when (actionIdString) {
        EngineConstants.GLOBAL_ACTION_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        EngineConstants.GLOBAL_ACTION_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        EngineConstants.GLOBAL_ACTION_RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        EngineConstants.GLOBAL_ACTION_NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        EngineConstants.GLOBAL_ACTION_QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        EngineConstants.GLOBAL_ACTION_POWER_DIALOG -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        EngineConstants.GLOBAL_ACTION_OPEN_APP_DRAWER -> -1 // Special case handled separately
        else -> -1
    }
    
    if (actionId == -1) {
        // Special handling for app drawer
        if (actionIdString == EngineConstants.GLOBAL_ACTION_OPEN_APP_DRAWER) {
            Log.d(EngineConstants.ENGINE_TAG, "Executing special global action: $actionIdString")
            val drawerSuccess = service.performOpenAppDrawer()
            return if (drawerSuccess) {
                 Log.i(EngineConstants.ENGINE_TAG, "Global action '$actionIdString' dispatched successfully.")
                 // No delay/overlay needed here as the action itself handles it
                 Pair(true, "Global action '$actionIdString' dispatched.")
            } else {
                 Log.e(EngineConstants.ENGINE_TAG, "Failed to dispatch special global action: $actionIdString")
                 Pair(false, "Failed to dispatch special global action: $actionIdString")
            }
        }
        return Pair(false, "Invalid or missing action ID: $actionIdString")
    }

    Log.d(EngineConstants.ENGINE_TAG, "Executing global action: $actionIdString ($actionId)")

    // Perform the global action using the internal service method
    val success = service.performGlobalActionInternal(actionId)

    if (success) {
        Log.i(EngineConstants.ENGINE_TAG, "Global action '$actionIdString' dispatched successfully.")
        return Pair(true, "Global action '$actionIdString' dispatched.")
    } else {
        Log.e(EngineConstants.ENGINE_TAG, "Failed to dispatch global action: $actionIdString")
        return Pair(false, "Failed to dispatch global action: $actionIdString")
    }
}

// REMOVED: handleStartActivityIntent

// REMOVED: handleRunPredefinedFunction

// handleSwipeSemantic: Updated to use suspend function
suspend fun handleSwipeSemantic(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    service ?: return Pair(false, "AccessibilityService unavailable for handleSwipeSemantic.")
    // context is unused here
    val direction = params.optString("direction", "").lowercase() // Use default empty string and then lowercase
    if (direction.isEmpty() || direction !in listOf("up", "down", "left", "right")) {
        return Pair(false, "Invalid or missing 'direction' for swipe (must be up/down/left/right).")
    }
    // Call the new suspend function directly
    val success = service.performGlobalSwipe(direction)
    return Pair(success, if (success) "Swipe $direction dispatched." else "Failed to dispatch swipe $direction.")
}


/**
 * Handles tapping an element based on its Selector.
 * Needs service, selector (parsed from params).
 */
suspend fun handleTapBySelector(service: AndroidUseAccessibilityService?, context: Context, selector: Selector): Pair<Boolean, String> {
    service ?: return Pair(false, "AccessibilityService unavailable for handleTapBySelector.")
    Log.i(EngineConstants.ENGINE_TAG, "Attempting tap by selector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
    
    // Pass the Selector object to the service's suspend method
    val success = service.performTapBySelector(selector)
    
    val resultMessage = if (success) "Tap by selector dispatched." else "Failed tap by selector."
    Log.d(EngineConstants.ENGINE_TAG, "handleTapBySelector: Returning success=$success, message='$resultMessage'")
    return Pair(success, resultMessage)
}

// handleLaunchApp: Needs context, params (JSONObject)
suspend fun handleLaunchApp(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    // service is unused here
    val packageName = params.optString("package_name")
    if (packageName.isNullOrEmpty()) {
        Log.e(EngineConstants.ENGINE_TAG, "handleLaunchApp: Missing package_name parameter.")
        return Pair(false, "Missing package_name parameter.")
    }

    // Activity name is optional as per plan
    val activityName: String? = params.optString("activity", null) // Explicitly nullable

    Log.i(EngineConstants.ENGINE_TAG, "Attempting to launch app: $packageName" + (activityName?.let { ", Activity: $it" } ?: ""))
    val packageManager: PackageManager = context.packageManager
    val intent: Intent? = if (activityName != null) {
        // If activity is specified, create a specific component intent
        try {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                // Ensure activityName is not null here due to outer check
                component = ComponentName(packageName, activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
             Log.e(EngineConstants.ENGINE_TAG, "handleLaunchApp: Error creating component intent for $packageName/$activityName", e)
             null // Fallback or fail
        }
    } else {
        // If no activity specified, use the default launch intent
        packageManager.getLaunchIntentForPackage(packageName)
    }


    if (intent == null) {
        Log.e(EngineConstants.ENGINE_TAG, "handleLaunchApp: Could not get or create launch intent for package '$packageName' (Activity: $activityName). App might not be installed or activity invalid.")
        return Pair(false, "App not found or cannot be launched: $packageName")
    }

    // Ensure the intent has the necessary flag to start a new task if not already set
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return try {
        context.startActivity(intent)
        Log.i(EngineConstants.ENGINE_TAG, "handleLaunchApp: Successfully requested activity start for package '$packageName'.")
        // Note: startActivity is asynchronous. Success here means the request was sent,
        // not necessarily that the app is fully loaded on screen.
        Pair(true, "App launch requested successfully: $packageName")
    } catch (e: ActivityNotFoundException) {
        Log.e(EngineConstants.ENGINE_TAG, "handleLaunchApp: ActivityNotFoundException for intent: $intent.", e)
        Pair(false, "Activity not found for package: $packageName (Activity: $activityName)")
    } catch (e: SecurityException) {
        Log.e(EngineConstants.ENGINE_TAG, "handleLaunchApp: SecurityException launching intent: $intent.", e)
        Pair(false, "Permission denied launching package: $packageName")
    } catch (e: Exception) {
        Log.e(EngineConstants.ENGINE_TAG, "handleLaunchApp: Unexpected exception launching intent: $intent.", e)
        Pair(false, "Error launching app $packageName: ${e.message}")
    }
}

// --- Device Control Handlers (Do not require service instance) ---

// handleSetVolume: Needs context, params (JSONObject)
suspend fun handleSetVolume(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    // service is unused here
    val streamTypeStr = params.optString("stream", "music").lowercase()
    val streamType = DeviceControlUtils.getAudioStreamType(streamTypeStr)
    if (streamType == -1) {
        return Pair(false, "Invalid audio stream type: $streamTypeStr")
    }
    return if (params.has("level")) {
        val levelPercent = params.optInt("level", -1)
        if (levelPercent < 0 || levelPercent > 100) {
            Pair(false, "Invalid volume level percentage: $levelPercent (must be 0-100)")
        } else {
            val success = DeviceControlUtils.setVolumeLevel(context, streamType, levelPercent)
            Pair(success, if (success) "Volume for $streamTypeStr set to $levelPercent%." else "Failed to set volume for $streamTypeStr.")
        }
    } else if (params.has("direction")) {
        val directionStr = params.optString("direction", "").lowercase()
        val direction = when(directionStr) {
            "up" -> android.media.AudioManager.ADJUST_RAISE
            "down" -> android.media.AudioManager.ADJUST_LOWER
            else -> {
                return Pair(false, "Invalid volume direction: $directionStr (must be up or down)")
            }
        }
        val success = DeviceControlUtils.adjustVolume(context, streamType, direction)
        Pair(success, if (success) "Volume for $streamTypeStr adjusted $directionStr." else "Failed to adjust volume for $streamTypeStr.")
    } else {
        Pair(false, "Missing 'level' or 'direction' parameter for set_volume.")
    }
}

// --- Other Handlers (Need service, selector/params) ---

// handleInputBySelector: Needs service, selector (parsed from params), text_to_type
suspend fun handleInputBySelector(service: AndroidUseAccessibilityService?, context: Context, selector: Selector, textToType: String): Pair<Boolean, String> {
    service ?: return Pair(false, "AccessibilityService unavailable for handleInputBySelector.")
    Log.i(EngineConstants.ENGINE_TAG, "Attempting input '$textToType' into selector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
    // Pass the Selector and text to the service method (to be implemented)
    val success = withContext(Dispatchers.Main) { service.performInputBySelector(selector, textToType) }
    return Pair(success, if (success) "Input '$textToType' via selector successful." else "Failed input via selector.")
}

// handleCopyBySelector: Needs service, selector (parsed from params)
suspend fun handleCopyBySelector(service: AndroidUseAccessibilityService?, context: Context, selector: Selector): Pair<Boolean, String> {
    service ?: return Pair(false, "AccessibilityService unavailable for handleCopyBySelector.")
    Log.i(EngineConstants.ENGINE_TAG, "Attempting copy from selector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
    // Use the suspend function directly
    val success = service.performCopyBySelector(selector)
    return Pair(success, if (success) "Copy via selector successful." else "Failed copy via selector.")
}

// handlePasteBySelector: Needs service, selector (parsed from params)
suspend fun handlePasteBySelector(service: AndroidUseAccessibilityService?, context: Context, selector: Selector): Pair<Boolean, String> {
    service ?: return Pair(false, "AccessibilityService unavailable for handlePasteBySelector.")
    Log.i(EngineConstants.ENGINE_TAG, "Attempting paste into selector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
    // Use the suspend function directly
    val success = service.performPasteBySelector(selector)
    return Pair(success, if (success) "Paste via selector successful." else "Failed paste via selector.")
}

// handleSelectBySelector: Needs service, selector (parsed from params), start/end
suspend fun handleSelectBySelector(service: AndroidUseAccessibilityService?, context: Context, selector: Selector, start: Int?, end: Int?): Pair<Boolean, String> {
    service ?: return Pair(false, "AccessibilityService unavailable for handleSelectBySelector.")
    Log.i(EngineConstants.ENGINE_TAG, "Attempting select [$start:$end] in selector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
    // Use the suspend function directly
    val success = service.performSelectBySelector(selector, start, end)
    return Pair(success, if (success) "Select [$start:$end] via selector successful." else "Failed select via selector.")
}

// handleLongClickBySelector: Needs service, selector (parsed from params)
suspend fun handleLongClickBySelector(service: AndroidUseAccessibilityService?, context: Context, selector: Selector): Pair<Boolean, String> {
    service ?: return Pair(false, "AccessibilityService unavailable for handleLongClickBySelector.")
    Log.i(EngineConstants.ENGINE_TAG, "Attempting long click on selector: ${selector.viewId ?: selector.text ?: selector.contentDesc ?: "(no identifier)"}")
    // Use the suspend function directly
    val success = service.performLongClickBySelector(selector)
    return Pair(success, if (success) "Long click via selector successful." else "Failed long click via selector.")
}


// --- DEPRECATED Index Handlers (Mark as Deprecated, Keep for reference/transition?) ---

@Deprecated("Use handleTapBySelector instead", ReplaceWith("handleTapBySelector(service, context, selector)"))
suspend fun handleTapByIndex(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    // ... implementation using index (kept for reference)
    service ?: return Pair(false, "AccessibilityService unavailable for handleTapByIndex.")
    val index = params.optInt("index", -1)
    Log.d(EngineConstants.ENGINE_TAG, "[DEPRECATED] handleTapByIndex: Received index = $index")
    if (index < 0) return Pair(false, "Invalid or missing 'index' parameter.")
    Log.i(EngineConstants.ENGINE_TAG, "[DEPRECATED] Attempting tap by index: $index")
    // REMOVE FAULTY CALL
    // val success = withContext(Dispatchers.Main) { service.performTapByIndex(index) }
    Log.e(EngineConstants.ENGINE_TAG, "[DEPRECATED] handleTapByIndex called, but underlying function is removed/broken.")
    val success = false
    val resultMessage = if (success) "Tap by index $index dispatched." else "Failed tap by index $index (DEPRECATED)."
    Log.d(EngineConstants.ENGINE_TAG, "[DEPRECATED] handleTapByIndex: Returning success=$success, message='$resultMessage'")
    return Pair(success, resultMessage)
}

@Deprecated("Use handleInputBySelector instead", ReplaceWith("handleInputBySelector(service, context, selector, textToType)"))
suspend fun handleInputByIndex(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    // ... implementation using index (kept for reference)
    service ?: return Pair(false, "AccessibilityService unavailable for handleInputByIndex.")
    val index = params.optInt("index", -1)
    val textToType: String? = params.optString("text_to_type", null)
    if (index < 0 || textToType == null) {
        Log.e(EngineConstants.ENGINE_TAG, "[DEPRECATED] handleInputByIndex: Invalid params - index=$index, textToType=$textToType")
        return Pair(false, "Invalid or missing 'index' or 'text_to_type' parameter.")
    }
    Log.i(EngineConstants.ENGINE_TAG, "[DEPRECATED] Attempting input '$textToType' into index: $index")
    // REMOVE FAULTY CALL
    // val success = withContext(Dispatchers.Main) { service.performInputByIndex(index, textToType) }
    Log.e(EngineConstants.ENGINE_TAG, "[DEPRECATED] handleInputByIndex called, but underlying function is removed/broken.")
    val success = false
    return Pair(success, if (success) "Input '$textToType' into index $index successful." else "Failed input into index $index (DEPRECATED)." )
}

@Deprecated("Use handleCopyBySelector instead", ReplaceWith("handleCopyBySelector(service, context, selector)"))
suspend fun handleCopyByIndex(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    // ... implementation using index (kept for reference)
    service ?: return Pair(false, "AccessibilityService unavailable for handleCopyByIndex.")
    val index = params.optInt("index", -1)
    if (index < 0) return Pair(false, "Invalid or missing 'index' parameter.")
    Log.i(EngineConstants.ENGINE_TAG, "[DEPRECATED] Attempting copy from index: $index")
    // REMOVE FAULTY CALL
    // val success = withContext(Dispatchers.Main) { service.performCopyOnNode(index) }
    Log.e(EngineConstants.ENGINE_TAG, "[DEPRECATED] handleCopyByIndex called, but underlying function is removed/broken.")
    val success = false
    return Pair(success, if (success) "Copy from index $index successful." else "Failed copy from index $index (DEPRECATED)." )
}

@Deprecated("Use handlePasteBySelector instead", ReplaceWith("handlePasteBySelector(service, context, selector)"))
suspend fun handlePasteByIndex(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    // ... implementation using index (kept for reference)
    service ?: return Pair(false, "AccessibilityService unavailable for handlePasteByIndex.")
    val index = params.optInt("index", -1)
    if (index < 0) return Pair(false, "Invalid or missing 'index' parameter.")
    Log.i(EngineConstants.ENGINE_TAG, "[DEPRECATED] Attempting paste into index: $index")
    // REMOVE FAULTY CALL
    // val success = withContext(Dispatchers.Main) { service.performPasteOnNode(index) }
    Log.e(EngineConstants.ENGINE_TAG, "[DEPRECATED] handlePasteByIndex called, but underlying function is removed/broken.")
    val success = false
    return Pair(success, if (success) "Paste into index $index successful." else "Failed paste into index $index (DEPRECATED)." )
}

@Deprecated("Use handleSelectBySelector instead", ReplaceWith("handleSelectBySelector(service, context, selector, start, end)"))
suspend fun handleSelectByIndex(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    // ... implementation using index (kept for reference)
    service ?: return Pair(false, "AccessibilityService unavailable for handleSelectByIndex.")
    val index = params.optInt("index", -1)
    if (index < 0) return Pair(false, "Invalid or missing 'index' parameter.")
    // Extract optional start/end for selection
    val start = params.optInt("start", -1).takeIf { it >= 0 }
    val end = params.optInt("end", -1).takeIf { it >= 0 }
    Log.i(EngineConstants.ENGINE_TAG, "[DEPRECATED] Attempting select [$start:$end] in index: $index")
    // REMOVE FAULTY CALL:
    // val success = withContext(Dispatchers.Main) { service.performSetSelectionOnNode(index, start, end) }
    // For now, just return failure as this function is deprecated and was broken
    Log.e(EngineConstants.ENGINE_TAG, "[DEPRECATED] handleSelectByIndex called, but underlying function is removed/broken.")
    val success = false
    return Pair(success, if (success) "Select [$start:$end] in index $index successful." else "Failed select in index $index (DEPRECATED)." )
}

@Deprecated("Use handleLongClickBySelector instead", ReplaceWith("handleLongClickBySelector(service, context, selector)"))
suspend fun handleLongClickByIndex(service: AndroidUseAccessibilityService?, context: Context, params: JSONObject): Pair<Boolean, String> {
    // ... implementation using index (kept for reference)
    service ?: return Pair(false, "AccessibilityService unavailable for handleLongClickByIndex.")
    val index = params.optInt("index", -1)
    if (index < 0) return Pair(false, "Invalid or missing 'index' parameter.")
    Log.i(EngineConstants.ENGINE_TAG, "[DEPRECATED] Attempting long click on index: $index")
    // REMOVE FAULTY CALL
    // val success = withContext(Dispatchers.Main) { service.performLongClickOnNode(index) }
    Log.e(EngineConstants.ENGINE_TAG, "[DEPRECATED] handleLongClickByIndex called, but underlying function is removed/broken.")
    val success = false
    return Pair(success, if (success) "Long click on index $index successful." else "Failed long click on index $index (DEPRECATED)." )
}

// Note: The actionHandlerMap is defined in ExecutionEngine.kt

/**
 * Handles the 'done' action from the server.
 * This action signifies the server-side agent believes the task is complete.
 * It doesn't perform any device action but acknowledges the state.
 */
suspend fun handleDone(engine: Any, params: JSONObject?, correlationId: String): Pair<Boolean, String> {
    val success = params?.optBoolean("success", true) ?: true // Default to success if not specified
    val message = params?.optString("message", "Task marked as done by server.") ?: "Task marked as done by server."
    Log.i(EngineConstants.ENGINE_TAG, "Handling 'done' action. Success: $success, Message: $message")
    
    // If engine is ExecutionEngine, send result
    if (engine is ExecutionEngine) {
        engine.sendResult(ActionResult(success = true, message = "Client acknowledged task completion."), correlationId)
    }
    
    return Pair(true, "Done action processed")
}

/**
 * Handles the 'wait' action from the server.
 * Pauses execution for a specified number of milliseconds.
 */
suspend fun handleWait(params: JSONObject): Pair<Boolean, String> {
    val duration = params.optLong("duration_ms", 1000)
    Log.i(EngineConstants.ENGINE_TAG, "Handling 'wait' action. Duration: $duration ms")
    
    try {
        Log.d(EngineConstants.TAG_ACTIONS, "Starting wait for ${duration}ms.")
        kotlinx.coroutines.delay(duration)
        Log.d(EngineConstants.TAG_ACTIONS, "Finished wait for ${duration}ms.")
        return Pair(true, "Waited for $duration ms")
    } catch (e: Exception) {
        Log.e(EngineConstants.ENGINE_TAG, "Error during wait: ${e.message}")
        return Pair(false, "Error during wait: ${e.message}")
    }
}