package com.yogen.Android_Use.accessibility.service_files

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

// Import the constant instead of redefining it
import com.yogen.Android_Use.accessibility.service_files.SERVICE_TAG

/**
 * Gets detailed screen metrics including width and height pixels.
 * Returns a DisplayMetrics object or null if unavailable.
 */
fun getDetailedDisplayMetrics(service: AccessibilityService): DisplayMetrics? {
    return try {
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            Log.e(SERVICE_TAG, "Failed to get WindowManager")
            return null
        }

        val metrics = DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            service.display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        metrics
    } catch (e: Exception) {
        Log.e(SERVICE_TAG, "Error getting display metrics: ${e.message}")
        null
    }
} 