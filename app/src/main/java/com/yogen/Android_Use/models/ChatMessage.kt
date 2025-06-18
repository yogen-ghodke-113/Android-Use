package com.yogen.Android_Use.models

/**
 * Represents a single message in the chat history, aligning with ViewModel data.
 *
 * @param message The textual content of the message.
 * @param type The type of the message (USER, ASSISTANT, STATUS, ERROR, CLARIFICATION_REQUEST).
 */
data class ChatMessage(
    val message: String,
    val type: MessageType
) {
    // Convenience properties (can be removed if ChatFragment is updated)
    val isUser: Boolean get() = type == MessageType.USER
    val isStatus: Boolean get() = type == MessageType.STATUS
    val isError: Boolean get() = type == MessageType.ERROR
    val isClarification: Boolean get() = type == MessageType.CLARIFICATION_REQUEST
} 