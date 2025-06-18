package com.yogen.Android_Use.models

import java.util.UUID

data class Message(
    val content: String,
    val messageType: MessageType, // Use the enum
    val isFromUser: Boolean, // Keep for UI differentiation if needed
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
) 