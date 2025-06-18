package com.yogen.Android_Use.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import com.yogen.Android_Use.R
import com.yogen.Android_Use.utils.AccessibilityUtils

/**
 * A non-dismissible dialog that prompts the user to enable accessibility service
 */
class AccessibilityPermissionDialog(context: Context) : Dialog(context) {
    
    private var onPermissionGrantedListener: (() -> Unit)? = null
    
    init {
        // Make dialog non-cancelable
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_accessibility_permission, null)
        setContentView(view)
        
        // Set up explanation text
        val tvExplanation = view.findViewById<TextView>(R.id.tvAccessibilityExplanation)
        tvExplanation.text = context.getString(R.string.accessibility_permission_explanation)
        
        // Set up buttons
        val btnOpenSettings = view.findViewById<Button>(R.id.btnOpenSettings)
        btnOpenSettings.setOnClickListener {
            // Open accessibility settings
            AccessibilityUtils.openAccessibilitySettings(context)
        }
    }
    
    /**
     * Set listener for when permission is granted
     */
    fun setOnPermissionGrantedListener(listener: () -> Unit) {
        onPermissionGrantedListener = listener
    }
    
    /**
     * Check if permission is granted and dismiss dialog accordingly
     */
    fun checkPermissionAndDismiss(): Boolean {
        val isEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(context)
        if (isEnabled) {
            dismiss()
            onPermissionGrantedListener?.invoke()
            return true
        }
        return false
    }
    
    /**
     * Prevent dialog dismissal via back button
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true  // Consume the back button event to prevent dismissal
        }
        return super.onKeyDown(keyCode, event)
    }
} 