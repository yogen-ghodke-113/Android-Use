package com.yogen.Android_Use.models

import android.graphics.Rect
import com.google.gson.annotations.SerializedName

/**
 * Data class representing a robust selector for an AccessibilityNodeInfo.
 * Used to reliably identify a UI element even if the UI tree changes.
 * Based on the playbook for stable selectors.
 */
data class Selector(
    @SerializedName("view_id") val viewId: String?,
    @SerializedName("text") val text: String?,
    @SerializedName("content_desc") val contentDesc: String?,
    @SerializedName("class_name") val className: String?,
    @SerializedName("window_id") val windowId: Int,
    // Note: getSourceNodeId() is a hidden API and unstable, omitted for now.
    // @SerializedName("node_id") val nodeId: Long?,
    @SerializedName("bounds") val bounds: Rect?, // Use android.graphics.Rect for simplicity
    @SerializedName("is_clickable") val isClickable: Boolean = false,
    @SerializedName("is_editable") val isEditable: Boolean = false,
    @SerializedName("is_long_clickable") val isLongClickable: Boolean = false
    // @SerializedName("screenshot_digest") val screenshotDigest: String? // Optional for future use
) 