package com.yogen.Android_Use.execution.engine_files

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.yogen.Android_Use.accessibility.AndroidUseAccessibilityService
import com.yogen.Android_Use.utils.AccessibilityUtils
import org.json.JSONException
import org.json.JSONObject
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ENGINE_TAG

// --- Helper Functions ---

/**
 * Returns a human-readable identifier for a node, useful for logging
 */
fun getNodeIdentifier(node: AccessibilityNodeInfo?): String {
    if (node == null) return "null"
    
    val sb = StringBuilder()
    try {
        val viewId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        
        sb.append("Node[")
        if (viewId.isNotEmpty()) sb.append("id=$viewId")
        if (text.isNotEmpty()) {
            if (sb.length > 5) sb.append(", ")
            sb.append("text='${text.take(20)}'")
            if (text.length > 20) sb.append("...")
        }
        if (contentDesc.isNotEmpty()) {
            if (sb.length > 5) sb.append(", ")
            sb.append("desc='${contentDesc.take(20)}'")
            if (contentDesc.length > 20) sb.append("...")
        }
        if (className.isNotEmpty()) {
            if (sb.length > 5) sb.append(", ")
            sb.append("class=$className")
        }
        sb.append("]")
    } catch (e: Exception) {
        // If any exception occurs during toString(), fall back to simple hash
        sb.clear()
        sb.append("Node@${node.hashCode()}")
    }
    
    return sb.toString()
}

/**
 * Checks if a package is installed on the device.
 *
 * @param packageManager PackageManager instance.
 * @param packageName The name of the package to check.
 * @return True if the package is installed, false otherwise.
 */
fun isPackageInstalled(packageManager: PackageManager, packageName: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        true // Package found
    } catch (e: PackageManager.NameNotFoundException) {
        false // Package not found
    }
}
