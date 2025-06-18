package com.yogen.Android_Use.execution.engine_files

// Define ALL Action Type Strings and other shared constants here ONCE
object EngineConstants {
    // --- Logging Tags ---
    const val ENGINE_TAG = "ExecutionEngine"
    const val API_CLIENT_TAG = "ApiClient"
    const val ACCESSIBILITY_TAG = "A11yService" // Renamed for consistency
    const val ACTIONS_TAG = "ActionHandlers"   // Renamed for consistency
    const val TAG_ACCESSIBILITY = ACCESSIBILITY_TAG // Added for compatibility
    const val TAG_ACTIONS = ACTIONS_TAG         // Added for compatibility
    const val SCREENSHOT_TAG = "ScreenCapture"

    // Action Types (Ensure these match server-side definitions)
    // Index-based actions (To be replaced by Selector actions)
    const val ACTION_TAP_BY_INDEX = "tap_by_index" // DEPRECATED - Prefer tap_by_selector
    const val ACTION_COPY_BY_INDEX = "copy_by_index" // DEPRECATED - Prefer copy_by_selector
    const val ACTION_PASTE_BY_INDEX = "paste_by_index" // DEPRECATED - Prefer paste_by_selector
    const val ACTION_SELECT_BY_INDEX = "select_by_index" // DEPRECATED - Prefer select_by_selector
    const val ACTION_LONG_CLICK_BY_INDEX = "long_click_by_index" // DEPRECATED - Prefer long_click_by_selector
    const val ACTION_INPUT_BY_INDEX = "input_by_index" // DEPRECATED - Prefer input_by_selector

    // --- NEW Selector-based Actions ---
    const val ACTION_TAP_BY_SELECTOR = "tap_by_selector"
    const val ACTION_INPUT_BY_SELECTOR = "input_by_selector"
    const val ACTION_COPY_BY_SELECTOR = "copy_by_selector"
    const val ACTION_PASTE_BY_SELECTOR = "paste_by_selector"
    const val ACTION_SELECT_BY_SELECTOR = "select_by_selector"
    const val ACTION_LONG_CLICK_BY_SELECTOR = "long_click_by_selector"
    // --- END Selector-based Actions ---

    // Non-selector actions (remain as is)
    const val ACTION_PERFORM_GLOBAL = "perform_global_action"
    const val ACTION_SWIPE = "swipe_semantic" // Renamed to match plan/server
    const val ACTION_LAUNCH_APP = "launch_app"
    const val ACTION_SET_VOLUME = "set_volume"
    const val ACTION_DONE = "done" // Added action for task completion

    // Predefined function names removed (PREDEFINED_FUNC_*)

    // --- Global Action IDs (Ensure these match server-side action_id values) ---
    const val GLOBAL_ACTION_BACK = "GLOBAL_ACTION_BACK"
    const val GLOBAL_ACTION_HOME = "GLOBAL_ACTION_HOME"
    const val GLOBAL_ACTION_RECENTS = "GLOBAL_ACTION_RECENTS"
    const val GLOBAL_ACTION_NOTIFICATIONS = "GLOBAL_ACTION_NOTIFICATIONS"
    const val GLOBAL_ACTION_QUICK_SETTINGS = "GLOBAL_ACTION_QUICK_SETTINGS"
    const val GLOBAL_ACTION_POWER_DIALOG = "GLOBAL_ACTION_POWER_DIALOG"
    const val GLOBAL_ACTION_OPEN_APP_DRAWER = "GLOBAL_ACTION_OPEN_APP_DRAWER" // New Action ID

    // Node Actions constants removed (use platform ones directly)

    // Scroll action direction constants removed (part of swipe parameters now)
}