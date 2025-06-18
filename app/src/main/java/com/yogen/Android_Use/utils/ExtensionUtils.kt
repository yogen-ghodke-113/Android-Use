package com.yogen.Android_Use.utils

import android.content.Context
import android.util.TypedValue

/**
 * Converts dp value to equivalent pixels
 */
fun Int.dpToPx(context: Context): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        context.resources.displayMetrics
    ).toInt()
} 