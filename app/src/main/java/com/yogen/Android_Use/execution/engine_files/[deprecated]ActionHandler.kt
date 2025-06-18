/*package com.yogen.Android_Use.execution.engine_files

import android.content.Context
import android.util.Log
import com.yogen.Android_Use.accessibility.AndroidUseAccessibilityService
import com.yogen.Android_Use.models.Selector
import org.json.JSONObject
import kotlinx.coroutines.delay // Required for delay

// ... existing imports and handler functions (like handleTapBySelector, etc.) ...

// This function is now implemented in ActionHandlers.kt
/*
suspend fun handleWait(params: JSONObject): Pair<Boolean,String> {
    val durationSeconds = params.optLong("duration_seconds", 1L) // Default to 1 second
    val ms = durationSeconds * 1000
    if (ms <= 0) {
        return false to "Wait duration must be positive."
    }
    try {
        delay(ms)
        return true to "Waited ${durationSeconds} second(s)."
    } catch (e: Exception) {
        return false to "Error during wait: ${e.message}"
    }
}
*/

// ... other existing handler functions ... 

suspend fun waitForCondition(params: JSONObject): Pair<Boolean, String> {
    val durationSeconds = params.optLong("duration_seconds", 1L) // Default to 1 second
    val ms = durationSeconds * 1000
    if (ms <= 0) {
        return false to "Wait duration must be positive."
    }
    var elapsedTime = 0L
    while (elapsedTime < ms) {
        val result = predicate()
        if (result) return true to "Condition met."
        Log.d("ActionHandler", "waitForCondition: predicate false, starting ${ms}ms delay...")
        delay(ms)
        Log.d("ActionHandler", "waitForCondition: finished ${ms}ms delay.")
        elapsedTime += ms
    }
    return false to "Condition not met within the specified duration."
} */