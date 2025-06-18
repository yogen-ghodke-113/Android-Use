package com.yogen.Android_Use.models

// Note: This is a simplified client-side representation.
// The server defines the full Pydantic model. This is mainly for ExecutionEngine.
data class ActionModel(
    val actionType: String,
    val parameters: Map<String, Any?>? // Keep parameters flexible
) 