package com.yogen.Android_Use.utils

import android.util.Log

/**
 * Simple singleton object to hold device screen metrics.
 */
object DeviceMetricsHolder {
    private const val TAG = "DeviceMetricsHolder"

    @Volatile var screenWidth: Int = 0
        private set
    @Volatile var screenHeight: Int = 0
        private set

    fun updateMetrics(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            screenWidth = width
            screenHeight = height
            Log.i(TAG, "Screen metrics updated: $width x $height")
        } else {
            Log.w(TAG, "Attempted to update metrics with invalid dimensions: $width x $height")
        }
    }

    fun getMetrics(): Pair<Int, Int> {
        if (screenWidth == 0 || screenHeight == 0) {
            Log.w(TAG, "Screen metrics accessed before being initialized or with invalid values!")
        }
        return Pair(screenWidth, screenHeight)
    }
} 