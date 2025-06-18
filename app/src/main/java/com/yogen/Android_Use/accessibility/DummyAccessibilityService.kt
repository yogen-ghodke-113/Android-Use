package com.yogen.Android_Use.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * A minimal accessibility service implementation for testing compilation issues.
 */
class DummyAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "DummyAccService"
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        configureService()
    }
    
    private fun configureService() {
        try {
            // Create a new service info 
            val config = AccessibilityServiceInfo()
            
            // Configure event types
            config.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            
            // Configure feedback
            config.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            
            // Set flags one by one instead of chaining operations
            var flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            
            // Set capabilities separately
            var capabilities = 0
            
            // Set the flags and capabilities
            config.flags = flags
            // Setting capabilities in one step without using property access or assignment
            setServiceInfo(AccessibilityServiceInfo().apply {
                eventTypes = config.eventTypes
                feedbackType = config.feedbackType
                flags = config.flags
                capabilities = capabilities
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring service", e)
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Empty implementation
    }
    
    override fun onInterrupt() {
        // Empty implementation
    }
} 