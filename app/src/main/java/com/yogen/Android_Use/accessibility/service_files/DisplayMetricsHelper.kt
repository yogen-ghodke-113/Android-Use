package com.yogen.Android_Use.accessibility.service_files

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.util.Log
import org.json.JSONObject

private const val HELPER_TAG = "DisplayMetricsHelper"

// Function to get display metrics and insets
fun getDetailedDisplayMetrics(context: Context): JSONObject {
    val metricsJson = JSONObject()
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(HELPER_TAG, "Using WindowMetrics API (API 30+)")
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            val insets = windowMetrics.windowInsets

            metricsJson.put("widthPixels", bounds.width())
            metricsJson.put("heightPixels", bounds.height())

            val density = context.resources.displayMetrics.density
            metricsJson.put("density", density)
            metricsJson.put("densityDpi", (density * 160).toInt())

            // Rotation
            val rotation = try { context.display?.rotation ?: Display.DEFAULT_DISPLAY } catch (e: UnsupportedOperationException) { Display.DEFAULT_DISPLAY }
            metricsJson.put("rotation", rotation)

            // Insets (convert to pixels)
            val statusBarInsets = insets.getInsets(WindowInsets.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars())
            // Use systemBars() for general safe area if specific ones are zero
            val systemBarInsets = insets.getInsets(WindowInsets.Type.systemBars())
            
            val cutout = insets.displayCutout // May be null

            // Use systemBars as fallback if specific insets are 0
            metricsJson.put("statusBarHeight", if (statusBarInsets.top > 0) statusBarInsets.top else systemBarInsets.top)
            metricsJson.put("navBarHeight", if (navBarInsets.bottom > 0) navBarInsets.bottom else systemBarInsets.bottom) 
            metricsJson.put("navBarLeft", if (navBarInsets.left > 0) navBarInsets.left else systemBarInsets.left)
            metricsJson.put("navBarRight", if (navBarInsets.right > 0) navBarInsets.right else systemBarInsets.right)
            
            metricsJson.put("cutoutTop", cutout?.safeInsetTop ?: 0)
            metricsJson.put("cutoutBottom", cutout?.safeInsetBottom ?: 0)
            metricsJson.put("cutoutLeft", cutout?.safeInsetLeft ?: 0)
            metricsJson.put("cutoutRight", cutout?.safeInsetRight ?: 0)

            Log.d(HELPER_TAG, "Insets - Status: ${statusBarInsets.top}, Nav Bottom: ${navBarInsets.bottom}, Nav Left: ${navBarInsets.left}, Nav Right: ${navBarInsets.right}")
            Log.d(HELPER_TAG, "Insets (SystemBars) - Top: ${systemBarInsets.top}, Bottom: ${systemBarInsets.bottom}, Left: ${systemBarInsets.left}, Right: ${systemBarInsets.right}")
            if (cutout != null) {
                 Log.d(HELPER_TAG, "Cutout - Top: ${cutout.safeInsetTop}, Bottom: ${cutout.safeInsetBottom}, Left: ${cutout.safeInsetLeft}, Right: ${cutout.safeInsetRight}")
            } else {
                 Log.d(HELPER_TAG, "Cutout - None")
            }

        } else {
            Log.d(HELPER_TAG, "Using deprecated DisplayMetrics API (pre-API 30)")
            // Fallback for older APIs
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.getRealMetrics(displayMetrics)

            metricsJson.put("widthPixels", displayMetrics.widthPixels)
            metricsJson.put("heightPixels", displayMetrics.heightPixels)
            metricsJson.put("density", displayMetrics.density)
            metricsJson.put("densityDpi", displayMetrics.densityDpi)
            @Suppress("DEPRECATION")
            val rotation = try { windowManager.defaultDisplay?.rotation ?: Display.DEFAULT_DISPLAY } catch (e: UnsupportedOperationException) { Display.DEFAULT_DISPLAY }
            metricsJson.put("rotation", rotation)

            // Insets are harder to get reliably pre-API 30
            metricsJson.put("statusBarHeight", getStatusBarHeight(context))
            metricsJson.put("navBarHeight", getNavigationBarHeight(context))
            metricsJson.put("cutoutTop", 0)
            metricsJson.put("cutoutBottom", 0)
            metricsJson.put("cutoutLeft", 0)
            metricsJson.put("cutoutRight", 0)
            Log.d(HELPER_TAG, "Insets (Legacy) - Status: ${getStatusBarHeight(context)}, Nav: ${getNavigationBarHeight(context)}")
        }
        metricsJson.put("success", true)
        Log.i(HELPER_TAG, "Successfully retrieved display metrics: ${metricsJson.toString(2)}")

    } catch (e: Exception) {
        Log.e(HELPER_TAG, "Error getting detailed display metrics", e)
        // Ensure basic structure exists even on error
        try {
             metricsJson.put("success", false)
             metricsJson.put("error", e.message ?: "Unknown error getting display metrics")
        } catch (jsonEx: Exception) {
            Log.e(HELPER_TAG, "Error putting error info into JSON", jsonEx)
        }
    }
    return metricsJson
}

// Helper for status bar height (pre-API 30)
private fun getStatusBarHeight(context: Context): Int {
    val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
}

// Helper for navigation bar height (pre-API 30) - might not be accurate on all devices
private fun getNavigationBarHeight(context: Context): Int {
    val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
} 