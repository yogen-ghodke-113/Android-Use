package com.yogen.Android_Use.models

enum class MessageType {
    USER,
    ASSISTANT,
    STATUS,
    ERROR,
    COMMAND, // Maybe add command type?
    CLARIFICATION_REQUEST, // For clarification requests from the server
    UNKNOWN // For handling unknown message types
} 