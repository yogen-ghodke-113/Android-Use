package com.yogen.Android_Use.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.yogen.Android_Use.models.Selector
import com.yogen.Android_Use.utils.dpToPx
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages an overlay that shows red bounding boxes around interactive elements.
 * Only works when called from within an AccessibilityService.
 */
class OverlayManager(private val context: Context) {
    private val TAG = "OverlayManager"
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayView: OverlayView = OverlayView(context)
    private val isOverlayAdded = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper()) // Handler for delayed removal
    private var hideRunnable: Runnable? = null
    
    // Calibration offsets
    // Positive X shifts boxes right, negative shifts left
    // Negative Y shifts boxes up, positive shifts down (Android Y-axis increases downward)
    var xOffset = 0
    var yOffset = -170 

    // Keep OverlayView internal
    private inner class OverlayView(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.RED
            strokeWidth = 4.dpToPx(context).toFloat() // 4dp thickness
            style = Paint.Style.STROKE
            alpha = 180 // Semi-transparent
        }
        // Store the ORIGINAL, un-offset boxes
        @Volatile
        var originalBoxes: List<Rect> = emptyList()
            // Simple setter, just update and invalidate
            set(value) {
                field = value
                invalidate() // Request redraw with new boxes
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Apply offsets dynamically here before drawing
            Log.d(TAG, "OverlayView onDraw: Drawing boxes. Current Offset: x=$xOffset, y=$yOffset") // Log offset
            if (originalBoxes.isEmpty()) {
                Log.d(TAG, "OverlayView onDraw: No boxes to draw.")
                return
            }
            originalBoxes.forEachIndexed { index, rect ->
                val adjustedRect = Rect(
                    rect.left + xOffset,
                    rect.top + yOffset,
                    rect.right + xOffset,
                    rect.bottom + yOffset
                )
                // Log first box details for debugging
                if (index == 0) {
                    Log.d(TAG, "OverlayView onDraw: Box 0 Original: $rect")
                    Log.d(TAG, "OverlayView onDraw: Box 0 Adjusted: $adjustedRect with Offset: x=$xOffset, y=$yOffset")
                }
                canvas.drawRect(adjustedRect, paint)
            }
        }
    }

    // Helper to convert Selector bounds to Rect
    private fun Selector.toAndroidRect(): Rect? {
        return this.bounds?.let { Rect(it.left, it.top, it.right, it.bottom) }
    }

    // Convert NodeInfo to Rect
    private fun AccessibilityNodeInfo.toAndroidRect(): Rect {
        val rect = Rect()
        getBoundsInScreen(rect)
        return rect
    }

    /**
     * Shows bounding boxes for the given selectors for a specific duration.
     */
    fun showBoundingBoxesForDuration(selectors: List<Selector>, durationMs: Long) {
        if (durationMs <= 0) return
        val rects = selectors.mapNotNull { it.toAndroidRect() }
        showOverlayWithRects(rects, durationMs)
    }

    /**
     * Shows bounding boxes for the given nodes for a specific duration.
     */
    fun showNodeBoundingBoxes(nodes: List<AccessibilityNodeInfo>, durationMs: Long) {
        if (durationMs <= 0) return
        val rects = nodes.mapNotNull { node ->
            try {
                node.toAndroidRect().takeUnless { it.isEmpty } // Ensure non-empty rects
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get bounds for node: ${getNodeIdentifier(node)}, Error: ${e.message}")
                null
            }
        }
        showOverlayWithRects(rects, durationMs)
    }

    /**
     * Sets calibration offsets for the overlay.
     */
    fun setCalibrationOffsets(x: Int, y: Int) {
        Log.i(TAG, "<<<<< setCalibrationOffsets CALLED: x=$x, y=$y (previous: x=$xOffset, y=$yOffset) >>>>>") // Changed Log level and added markers
        this.xOffset = x
        this.yOffset = y
        // Force redraw if overlay is currently visible
        if (isOverlayAdded.get()) {
            // Just invalidate, onDraw will use the new offsets
            Log.d(TAG, "setCalibrationOffsets: Overlay is visible, invalidating view.")
            handler.post { overlayView.invalidate() }
        } else {
            Log.d(TAG, "setCalibrationOffsets: Overlay not visible, invalidate skipped.")
        }
    }

    private fun showOverlayWithRects(rects: List<Rect>, durationMs: Long) {
        Log.d(TAG, "Showing overlay with ${rects.size} rects for ${durationMs}ms. Current offsets: x=$xOffset, y=$yOffset")
        handler.removeCallbacks(hideRunnable ?: Runnable {}) // Cancel previous hide task

        // Set the ORIGINAL boxes - onDraw will apply offsets
        overlayView.originalBoxes = rects

        if (isOverlayAdded.compareAndSet(false, true)) {
            Log.d(TAG, "Adding overlay view to WindowManager.")
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY // Requires API 26
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSPARENT
            )
            handler.post { windowManager.addView(overlayView, layoutParams) }
        } else {
            // Already added, invalidate needed to redraw with new boxes/offsets
            Log.d(TAG, "Overlay view already added, invalidating.")
            handler.post { overlayView.invalidate() }
        }

        // Schedule the hide action
        hideRunnable = Runnable { hideOverlay() }
        handler.postDelayed(hideRunnable!!, durationMs)
    }

    /**
     * Hides the overlay immediately and cancels any pending hide actions.
     */
    fun hideOverlay() {
        Log.d(TAG, "Hiding overlay.")
        handler.removeCallbacks(hideRunnable ?: Runnable {}) // Cancel pending hide
        if (isOverlayAdded.compareAndSet(true, false)) {
            Log.d(TAG, "Removing overlay view from WindowManager.")
            handler.post { windowManager.removeView(overlayView) }
        }
        overlayView.originalBoxes = emptyList() // Clear boxes
    }

    // Helper to get node identifier for logging (implementation needed or reuse from AccessibilityService)
    private fun getNodeIdentifier(node: AccessibilityNodeInfo?): String {
        node ?: return "(null node)"
        return try {
            val sb = StringBuilder()
            if (!node.viewIdResourceName.isNullOrEmpty()) sb.append("id:" + node.viewIdResourceName)
            if (node.text != null) sb.append(" text:'" + node.text + "'")
            if (node.contentDescription != null) sb.append(" desc:'" + node.contentDescription + "'")
            if (sb.isEmpty()) sb.append("class:" + node.className)
            sb.toString()
        } catch (e: Exception) {
            "(error getting identifier)"
        }
    }
} 