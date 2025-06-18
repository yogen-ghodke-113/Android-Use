package com.yogen.Android_Use.execution

/**
 * Represents the result of an action execution.
 * Used to communicate execution results back to the server.
 */
data class ActionResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any>? = null
)