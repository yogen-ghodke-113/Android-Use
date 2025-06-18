package com.yogen.Android_Use.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.yogen.Android_Use.models.Selector

/**
 * A stub accessibility service to test compilation.
 */
class MinimumAccessibilityService : AccessibilityService() {
    
    companion object {
        const val TAG = "MinAccService"
        var instance: MinimumAccessibilityService? = null
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service connected")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }
    
    override fun onInterrupt() {
        // No-op
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
    
    fun performTapBySelector(selector: Selector): Boolean {
        return false  // Stub implementation
    }
} 