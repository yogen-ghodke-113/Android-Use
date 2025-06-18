package com.yogen.Android_Use.models

/**
 * Represents the result of an action execution.
 * @property success Whether the action was successful
 * @property message Additional information about the action result
 * @property data Optional data map for additional result information
 */
data class ActionResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any?>? = null // Optional data map with default value
) 