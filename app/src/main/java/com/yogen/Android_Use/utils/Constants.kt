package com.yogen.Android_Use.utils

// Top-level Constants object
object Constants {
    // Default server URL - users should update this to their deployed server
    // For local development: "ws://127.0.0.1:8000" (or "ws://10.0.2.2:8000" for Android Emulator)
    // For deployed server: "wss://your-server-domain.com" (use wss:// for HTTPS deployments)
    const val SERVER_URL = "ws://127.0.0.1:8000"
}

// EngineConstants and AccessibilityConstants objects removed as they are deprecated.
// Current constants are defined in execution/engine_files/EngineConstants.kt