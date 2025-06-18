package com.yogen.Android_Use.accessibility.service_files

// --- Constants ---
const val SERVICE_TAG = "AndroidUseAccessibilityService" // Use a shared TAG prefix maybe

// Constants for broadcast intents
const val ACTION_ACCESSIBILITY_CONNECTED = "com.yogen.Android_Use.ACCESSIBILITY_CONNECTED"
const val ACTION_ACCESSIBILITY_DISCONNECTED = "com.yogen.Android_Use.ACCESSIBILITY_DISCONNECTED"
const val ACTION_SCREENSHOT_TAKEN = "com.yogen.Android_Use.SCREENSHOT_TAKEN"

// Keys for screenshot extras
const val EXTRA_SCREENSHOT_PATH = "screenshot_path"
const val EXTRA_SCREENSHOT_SUCCESS = "screenshot_success"
const val EXTRA_SCREENSHOT_ERROR = "screenshot_error"

// Deprecated data classes (UiElement, IndexedElementNode) and RectSerializer removed.
// Deprecated/Commented-out constants also removed.

