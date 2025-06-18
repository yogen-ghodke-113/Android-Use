package com.yogen.Android_Use.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.yogen.Android_Use.accessibility.AndroidUseAccessibilityService

/**
 * Utility class for accessibility-related functions
 */
object AccessibilityUtils {
    
    /**
     * Check if the accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        
        return enabledServices.any {
            it.id.contains(context.packageName) && it.id.contains("AndroidUseAccessibilityService")
        }
    }
    
    /**
     * Open accessibility settings
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
    
    /**
     * Generate the accessibility service class name
     */
    fun getAccessibilityServiceClassName(context: Context): String {
        return "${context.packageName}/${AndroidUseAccessibilityService::class.java.name}"
    }
} 