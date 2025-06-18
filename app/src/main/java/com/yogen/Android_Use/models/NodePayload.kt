package com.yogen.Android_Use.models

import android.graphics.Rect

/**
 * Data class representing the essential properties of an AccessibilityNodeInfo
 * sent to the server.
 */
data class NodePayload(
    val index: Int,
    val text: String? = null,
    val contentDesc: String? = null,
    val viewId: String? = null,
    val className: String,
    val packageName: String,
    val clickable: Boolean,
    val focusable: Boolean,
    val visible: Boolean,
    val enabled: Boolean,
    val bounds: Rect, // android.graphics.Rect
    // Added back properties based on user request
    val isLongClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isFocused: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isPassword: Boolean
    // Note: childrenIndices is not added back by default as it might not be needed
    // and adds complexity. Add explicitly if required.
) 