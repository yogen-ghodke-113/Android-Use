package com.yogen.Android_Use.execution

/**
 * Represents a command received from the server to be executed by the ExecutionEngine.
 *
 * @param actionType The type of action to perform (e.g., "tap_screen_coordinates", "input_text").
 *                   Should match constants defined in EngineConstants.
 * @param parameters A map containing the necessary parameters for the action.
 *                   Keys should match constants defined in EngineConstants (e.g., "x_px", "text").
 *                   Values can be of various types (Int, String, Double, etc.).
 */
data class CommandPayload(
    val actionType: String,
    val parameters: Map<String, Any?> // Use Any? for flexibility
) 