package com.yogen.Android_Use.api // Or your actual API package

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.yogen.Android_Use.app.AndroidUseApplication
import com.yogen.Android_Use.execution.ActionResult
import com.yogen.Android_Use.execution.CommandPayload
import com.yogen.Android_Use.execution.ExecutionEngine
import com.yogen.Android_Use.models.NodePayload
import com.yogen.Android_Use.models.Selector
import com.yogen.Android_Use.ui.chat.ChatViewModel
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.yogen.Android_Use.accessibility.AndroidUseAccessibilityService
import android.os.Bundle

// Define Interface (optional but good practice)
interface ApiClientInterface {
    fun connect(serverUrl: String)
    fun disconnect()
    fun isConnected(): Boolean
    fun sendInputMessage(message: String)
    fun sendClarificationResponse(response: String)
    fun sendExecutionResult(result: ActionResult, correlationId: String) // Now requires correlationId
    fun sendScreenshotResult(base64Data: String?, correlationId: String?, success: Boolean, message: String) // Accepts nullable correlationId
    fun sendUiDumpResult(dump: List<NodePayload>?, correlationId: String?, success: Boolean, message: String)
    fun sendNodeListResult(type: String, nodes: List<Selector>?, correlationId: String?, success: Boolean, message: String)
    fun sendPackagesResult(packages: List<String>?, correlationId: String?, success: Boolean, message: String)
    fun sendClientError(errorMessage: String)
}


class ApiClient private constructor( // Make constructor private for singleton
    private val context: Context,
    private val listener: ConnectionListener,
    private val executionEngine: ExecutionEngine
) : ApiClientInterface { // Implement the interface

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onError(error: String)
        fun onStatusUpdate(message: String)
        fun onClarificationRequest(question: String)
        fun onDialogResponse(message: String)
    }

    private var webSocket: WebSocket? = null
    // Create OkHttpClient once
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    var currentSessionId: String? = null
        private set
    private var isManuallyConnecting = false
    @Volatile private var isConnectedInternal = false
    private val gson = Gson()

    // Store correlation ID for the *specific* action currently being executed
    private val correlationIdForExecution = ConcurrentHashMap<String, String>() // Simple key "execute" for now

    companion object {
        @Volatile private var instance: ApiClient? = null

        // --- Singleton Access ---
        fun getInstance(appContext: Context): ApiClient {
            return instance ?: synchronized(this) {
                 (appContext.applicationContext as? AndroidUseApplication)?.apiClient
                     ?: throw IllegalStateException("ApiClient singleton not initialized in Application class. Ensure Application class is setup correctly.")
             }
        }

        // --- Method to initialize singleton (called from Application class) ---
         fun initialize(
             context: Context,
             listener: ConnectionListener,
             executionEngine: ExecutionEngine
         ): ApiClient {
             return synchronized(this) {
                 if (instance == null) {
                     instance = ApiClient(context.applicationContext, listener, executionEngine)
                 }
                 instance!!
             }
         }
        // --------------------------


        private const val TAG = "ApiClient"
        // Broadcast Actions (used by ApiClient to trigger actions in Activity/Service)
        internal const val ACTION_REQUEST_SCREENSHOT = "com.yogen.Android_Use.REQUEST_SCREENSHOT"
        internal const val ACTION_REQUEST_UI_DUMP = "com.yogen.Android_Use.REQUEST_UI_DUMP"
        internal const val ACTION_REQUEST_NODES_BY_TEXT = "com.yogen.Android_Use.REQUEST_NODES_BY_TEXT"
        internal const val ACTION_REQUEST_INTERACTIVE_NODES = "com.yogen.Android_Use.REQUEST_INTERACTIVE_NODES"
        internal const val ACTION_REQUEST_ALL_NODES = "com.yogen.Android_Use.REQUEST_ALL_NODES"
        internal const val ACTION_REQUEST_LIST_PACKAGES = "com.yogen.Android_Use.REQUEST_LIST_PACKAGES"
        internal const val ACTION_REQUEST_CLICKABLE_NODES = "com.yogen.Android_Use.REQUEST_CLICKABLE_NODES"
        // Intent Extras (used to pass data with broadcasts)
        internal const val EXTRA_CORRELATION_ID = "correlation_id"
        internal const val EXTRA_TEXT_QUERY = "text_query"
        internal const val EXTRA_INCLUDE_SYSTEM_APPS = "include_system_apps"
        internal const val EXTRA_PACKAGE_NAME_FILTER = "package_name_filter"

        // Server Message Types (Incoming)
        private const val TYPE_STATUS = "status"
        private const val TYPE_ERROR = "error"
        private const val TYPE_EXECUTE = "execute"
        private const val TYPE_REQUEST_SCREENSHOT = "request_screenshot"
        private const val TYPE_REQUEST_UI_DUMP = "request_indexed_ui_dump"
        internal const val TYPE_REQUEST_NODES_BY_TEXT = "request_nodes_by_text"
        internal const val TYPE_REQUEST_INTERACTIVE_NODES = "request_interactive_nodes"
        internal const val TYPE_REQUEST_ALL_NODES = "request_all_nodes"
        private const val TYPE_REQUEST_LIST_PACKAGES = "request_list_packages"
        private const val TYPE_CLARIFICATION_REQUEST = "clarification_request"
        private const val TYPE_DIALOG_RESPONSE = "dialog_response"
        internal const val TYPE_REQUEST_CLICKABLE_NODES = "request_clickable_nodes"

        // Client Message Types (Outgoing)
        private const val TYPE_EXECUTION_RESULT = "execution_result"
        private const val TYPE_SCREENSHOT_RESULT = "screenshot_result"
        private const val TYPE_UI_DUMP_RESULT = "indexed_ui_dump_result"
        internal const val TYPE_NODES_BY_TEXT_RESULT = "nodes_by_text_result"
        internal const val TYPE_INTERACTIVE_NODES_RESULT = "interactive_nodes_result"
        internal const val TYPE_ALL_NODES_RESULT = "all_nodes_result"
        private const val TYPE_LIST_PACKAGES_RESULT = "list_packages_result"
        private const val TYPE_CLARIFICATION_RESPONSE = "clarification_response"
        private const val TYPE_SESSION_CONNECT = "session_connect"
        private const val TYPE_CLASSIFY_INPUT = "classify_input" // Assuming client sends this for user input
        internal const val TYPE_CLICKABLE_NODES_RESULT = "clickable_nodes_result"
    }


    override fun connect(serverUrl: String) {
        if (isConnectedInternal || isManuallyConnecting) {
            Log.w(TAG, "WebSocket already connected or connection attempt in progress.")
            return
        }
        isManuallyConnecting = true
        if (currentSessionId == null) {
            currentSessionId = UUID.randomUUID().toString()
            Log.i(TAG, "Generated new session ID for connect: $currentSessionId")
        }
        val fullUrl = "$serverUrl/ws/$currentSessionId"
        Log.i(TAG, "Attempting to connect to WebSocket: $fullUrl")
        val request = Request.Builder().url(fullUrl).build()

        // Use the existing client instance
        webSocket = client.newWebSocket(request, webSocketListener)
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket Connection Opened")
            isConnectedInternal = true
            isManuallyConnecting = false // Connection successful
            sendSessionConnect() // Send initial connect message
            listener.onConnected() // Call listener
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!text.contains("image_base64") && text.length < 1000) {
                 Log.d(TAG, "WebSocket Message Received: $text")
             } else if (text.contains("image_base64")) {
                 Log.d(TAG, "WebSocket Message Received (screenshot type, size: ${text.length})")
             } else {
                 Log.d(TAG, "WebSocket Message Received (large, size: ${text.length})")
             }
             handleServerMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "WebSocket Binary Message Received (Size: ${bytes.size}) - Ignoring")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket Closing: Code=$code, Reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket Connection Closed: Code=$code, Reason=$reason")
             val displayReason = reason.ifEmpty { "No specific reason provided." }
             cleanupConnection("Connection closed: $displayReason (Code: $code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val errorMsg = "WebSocket Failure: ${t.message ?: "Unknown error"}"
            Log.e(TAG, errorMsg, t)
             val wasConnecting = isManuallyConnecting
             val wasConnected = isConnectedInternal
             cleanupConnection(errorMsg)
            if (wasConnecting) {
                 listener.onError(errorMsg)
             } else if (wasConnected) {
                 listener.onDisconnected(errorMsg)
             } else {
                 // If neither connecting nor connected, maybe initial error?
                 // Consider calling listener.onError(errorMsg) here too?
             }
        }
    }

    private fun cleanupConnection(disconnectReason: String) {
         // Should only be called ONCE per disconnect/failure
         if (!isConnectedInternal && !isManuallyConnecting) {
             Log.w(TAG, "cleanupConnection called but already cleaned up.")
                return
            }
         val wasConnected = isConnectedInternal
         webSocket?.cancel() // Attempt graceful cancel
         webSocket = null
         isConnectedInternal = false
         isManuallyConnecting = false
         correlationIdForExecution.clear() // Clear any pending execution correlation ID
         if (wasConnected) {
             listener.onDisconnected(disconnectReason)
         }
     }

    override fun disconnect() {
        Log.i(TAG, "Manual disconnect requested.")
        if (!isConnectedInternal) {
            Log.w(TAG, "Disconnect requested, but already disconnected.")
            isManuallyConnecting = false // Ensure flag is reset if disconnect called during failed attempt
            return
        }
        cleanupConnection("Manual disconnection.")
    }

    override fun isConnected(): Boolean = isConnectedInternal

    private fun sendJson(jsonObject: Map<String, Any?>) {
        if (!isConnectedInternal) {
            Log.e(TAG, "Cannot send message, WebSocket is not connected.")
            // Optionally notify listener or try reconnecting?
                return
        }
        try {
            val jsonString = gson.toJson(jsonObject)
            if (jsonString.length < 500) { // Don't log large payloads like screenshots
                Log.d(TAG, "Sending JSON: $jsonString")
            } else {
                Log.d(TAG, "Sending JSON (Type: ${jsonObject["type"] ?: "Unknown"}, Size: ${jsonString.length})")
            }
            webSocket?.send(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending JSON message: ${e.message}", e)
            // Handle error, maybe disconnect or notify listener
            cleanupConnection("Error sending message: ${e.message}")
        }
    }

    // --- Specific Send Methods ---

    override fun sendInputMessage(message: String) {
        val payload = mapOf(
            "type" to TYPE_CLASSIFY_INPUT,
            "session_id" to currentSessionId,
            "content" to mapOf("text" to message)
        )
        sendJson(payload)
    }

    override fun sendClarificationResponse(response: String) {
        val payload = mapOf(
            "type" to TYPE_CLARIFICATION_RESPONSE,
            "session_id" to currentSessionId,
            "content" to mapOf("text" to response)
        )
        sendJson(payload)
    }

    private fun sendSessionConnect() {
        if (currentSessionId == null) {
            Log.e(TAG, "Cannot send session connect, session ID is null.")
            return
        }
        val payload = mapOf(
            "type" to TYPE_SESSION_CONNECT,
            "session_id" to currentSessionId
        )
        sendJson(payload)
    }

    override fun sendExecutionResult(result: ActionResult, correlationId: String) {
        val payload = mapOf(
            "type" to TYPE_EXECUTION_RESULT,
            "session_id" to currentSessionId,
            "correlation_id" to correlationId,
            "content" to mapOf(
                "success" to result.success,
                "message" to result.message
            )
        )
        // --- NEW LOGGING ---
        try {
            val jsonString = gson.toJson(payload)
            Log.d(TAG, "Sending execution_result JSON: $jsonString")
        } catch (e: Exception) {
            Log.e(TAG, "Error converting execution_result payload to JSON for logging", e)
        }
        // --- END NEW LOGGING ---
        sendJson(payload)
    }

    override fun sendScreenshotResult(base64Data: String?, correlationId: String?, success: Boolean, message: String) {
        val payload = mutableMapOf<String, Any?>(
            "type" to TYPE_SCREENSHOT_RESULT,
            "session_id" to currentSessionId,
            "content" to mutableMapOf<String, Any?>(
            "success" to success,
            "message" to message
            ).apply {
                if (base64Data != null) {
                    put("image_base64", base64Data)
                }
            }
        )
        if (correlationId != null) {
            payload["correlation_id"] = correlationId
         }
        sendJson(payload)
    }

    override fun sendUiDumpResult(dump: List<NodePayload>?, correlationId: String?, success: Boolean, message: String) {
        // This sends the result for the older "request_indexed_ui_dump"
        val payload = mutableMapOf<String, Any?>(
            "type" to TYPE_UI_DUMP_RESULT,
            "session_id" to currentSessionId,
            "content" to mutableMapOf<String, Any?>(
            "success" to success,
            "message" to message
            ).apply {
                if (dump != null) {
                    put("nodes", dump)
                }
            }
        )
        if (correlationId != null) {
            payload["correlation_id"] = correlationId
         }
        sendJson(payload)
    }

    override fun sendNodeListResult(type: String, nodes: List<Selector>?, correlationId: String?, success: Boolean, message: String) {
        // The 'type' parameter received here is ALREADY the correct RESULT type (e.g., "clickable_nodes_result")
        // determined by the caller (handleNodeRequest). The mapping logic here was redundant and incorrect.
        Log.d(TAG, "sendNodeListResult sending with pre-mapped type: '$type'")

        val payload = mutableMapOf<String, Any?>(
            "type" to type, // Use the type parameter directly
            "session_id" to currentSessionId,
            "content" to mutableMapOf<String, Any?>(
            "success" to success,
            "message" to message
            ).apply {
                if (nodes != null) {
                    put("nodes", nodes)
                }
            }
        )
        if (correlationId != null) {
            payload["correlation_id"] = correlationId
         }
        sendJson(payload)
    }

    override fun sendPackagesResult(packages: List<String>?, correlationId: String?, success: Boolean, message: String) {
        val payload = mutableMapOf<String, Any?>(
            "type" to TYPE_LIST_PACKAGES_RESULT,
            "session_id" to currentSessionId,
            "content" to mutableMapOf<String, Any?>(
             "success" to success,
             "message" to message
            ).apply {
                if (packages != null) {
                    put("packages", packages)
                }
            }
         )
        if (correlationId != null) {
            payload["correlation_id"] = correlationId
         }
        sendJson(payload)
     }

    override fun sendClientError(errorMessage: String) {
         // Send an error message that originated purely on the client
         Log.w(TAG, "Sending client-side error to server: $errorMessage")
         val payload = mapOf(
             "type" to "client_error", // Use a distinct type?
             "session_id" to currentSessionId,
             "content" to mapOf(
                 "message" to errorMessage
             )
         )
         sendJson(payload)
     }

    // --- Message Handling ---

    private fun sendRequestBroadcast(action: String, correlationId: String?, extras: Map<String, String?>? = null) {
        Log.d(TAG, "Sending broadcast for action: $action, correlationId: $correlationId")
        val intent = Intent(action)
        if (correlationId != null) {
            intent.putExtra(EXTRA_CORRELATION_ID, correlationId)
        }
        extras?.forEach { (key, value) ->
            if (value != null) {
                intent.putExtra(key, value)
            }
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    private fun handleServerMessage(text: String) {
        try {
            val messageObject = gson.fromJson(text, JsonObject::class.java)
            val type = messageObject.get("type")?.asString ?: ""
            val contentElement = messageObject.get("content") // Keep as JsonElement
            val correlationId = messageObject.get("correlation_id")?.asString // Optional

            // Use main scope for UI/listener updates, IO for engine processing
            GlobalScope.launch(Dispatchers.Main) {
                when (type) {
                    TYPE_STATUS -> {
                        // --- RE-FIXED STATUS HANDLING ---
                        // Status messages have 'message' directly under the root, not in 'content'
                        val messageText = messageObject.get("message")?.asString
                        listener.onStatusUpdate(messageText ?: "Status update received without message....") // Pass extracted message or default
                        // --- END RE-FIXED STATUS HANDLING ---
                    }
                    TYPE_ERROR -> {
                        // Error messages might also have 'message' directly? Check server logs if errors occur.
                        // Assuming they follow the 'content' structure for now.
                        val errorMessage = contentElement?.takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.asString ?: "Unknown server error."
                        listener.onError(errorMessage)
                    }
                    TYPE_EXECUTE -> {
                        if (contentElement != null) {
                            val actionType = contentElement.asJsonObject.get("action_type")?.asString
                            val parameters = contentElement.asJsonObject.get("parameters")?.asJsonObject
                            Log.d(TAG, "Received execute command: Type=$actionType, Params=$parameters, CorrelationID=$correlationId")
                            if (actionType != null && correlationId != null) {
                                val appScope = (context.applicationContext as? AndroidUseApplication)?.applicationScope
                                if (appScope != null) {
                                    appScope.launch { 
                                        try {
                                            // --- FIXED JSON CONVERSION ---
                                            // Convert Gson JsonObject? to org.json.JSONObject?
                                            val paramsJson: JSONObject? = parameters?.let { gsonObject ->
                                                try {
                                                    JSONObject(gsonObject.toString()) // Convert Gson JsonObject to String, then parse as org.json.JSONObject
                                                } catch (e: org.json.JSONException) {
                                                    Log.e(TAG, "Failed to convert Gson parameters to org.json.JSONObject", e)
                                                    null // Failed conversion
                                                }
                                            }
                                            // --- END FIXED JSON CONVERSION ---

                                            Log.d(TAG, "Dispatching command '$actionType' to ExecutionEngine with params: $paramsJson")
                                            // Call updated processCommand
                                            withContext(Dispatchers.IO) {
                                                executionEngine.processCommand(actionType, paramsJson, correlationId)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error converting params or dispatching command '$actionType' in coroutine", e)
                                            sendExecutionResult(
                                                ActionResult(success = false, message = "Client internal error preparing/dispatching command: ${e.message}"),
                                                correlationId
                                            )
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "Application scope not found! Cannot launch command execution for '$actionType'.")
                                    sendExecutionResult(
                                        ActionResult(success = false, message = "Client internal error: Application scope missing."),
                                        correlationId
                                    )
                                }
                            } else {
                                Log.e(TAG, "Invalid execute command format. Type ($actionType) or correlationId ($correlationId) missing.")
                                if (correlationId != null) {
                                    sendExecutionResult(
                                        ActionResult(success = false, message = "Invalid command format received from server."),
                                        correlationId
                                    )
                                } else {
                                    sendClientError("Received invalid execute command from server (missing correlationId).")
                                }
                            }
                        } else {
                             Log.e(TAG, "Execute message received with null content.")
                             if (correlationId != null) {
                                sendExecutionResult(
                                    ActionResult(success = false, message = "Execute command received with null content."),
                                    correlationId
                                )
                            } else {
                                sendClientError("Received execute command with null content and no correlationId.")
                            }
                        }
                    }
                    TYPE_REQUEST_SCREENSHOT -> {
                        Log.d(TAG, "Received request_screenshot (CorrId: $correlationId)")
                        sendRequestBroadcast(ACTION_REQUEST_SCREENSHOT, correlationId)
                    }
                    TYPE_REQUEST_UI_DUMP -> {
                        // This handles the old "request_indexed_ui_dump" type name
                        Log.d(TAG, "Received request_indexed_ui_dump (Correlation ID: $correlationId)")
                        // Keep using broadcast for this deprecated type, or update if needed
                        sendRequestBroadcast(ACTION_REQUEST_UI_DUMP, correlationId)
                    }
                    // --- DIRECT CALL HANDLERS --- 
                    TYPE_REQUEST_NODES_BY_TEXT -> {
                        val textQuery = contentElement?.asJsonObject?.get("text")?.asString
                        Log.d(TAG, "Received request_nodes_by_text (Query: '$textQuery', Correlation ID: $correlationId)")
                        val serviceInstance = AndroidUseAccessibilityService.instance
                        if (serviceInstance != null && textQuery != null) {
                            val extras = Bundle().apply { putString(EXTRA_TEXT_QUERY, textQuery) }
                            // DIRECT CALL
                            serviceInstance.handleNodeRequest(ACTION_REQUEST_NODES_BY_TEXT, correlationId, extras)
                        } else {
                            val errorMsg = if (serviceInstance == null) "Client Error: Accessibility service not running or instance unavailable." else "Client Error: Missing 'text' parameter."
                            Log.e(TAG, "Cannot handle request_nodes_by_text: $errorMsg")
                            sendNodeListResult(TYPE_REQUEST_NODES_BY_TEXT, null, correlationId, false, errorMsg)
                        }
                    }
                    TYPE_REQUEST_INTERACTIVE_NODES -> {
                        Log.d(TAG, "Received request_interactive_nodes (Correlation ID: $correlationId)")
                        val serviceInstance = AndroidUseAccessibilityService.instance
                        if (serviceInstance != null) {
                             // DIRECT CALL
                            serviceInstance.handleNodeRequest(ACTION_REQUEST_INTERACTIVE_NODES, correlationId, null)
                        } else {
                            val errorMsg = "Client Error: Accessibility service not running or instance unavailable."
                            Log.e(TAG, "Cannot handle request_interactive_nodes: $errorMsg")
                            sendNodeListResult(TYPE_REQUEST_INTERACTIVE_NODES, null, correlationId, false, errorMsg)
                        }
                    }
                    TYPE_REQUEST_ALL_NODES -> {
                        Log.d(TAG, "Received request_all_nodes (Correlation ID: $correlationId)")
                         val serviceInstance = AndroidUseAccessibilityService.instance
                        if (serviceInstance != null) {
                             // DIRECT CALL
                            serviceInstance.handleNodeRequest(ACTION_REQUEST_ALL_NODES, correlationId, null)
                        } else {
                            val errorMsg = "Client Error: Accessibility service not running or instance unavailable."
                            Log.e(TAG, "Cannot handle request_all_nodes: $errorMsg")
                            sendNodeListResult(TYPE_REQUEST_ALL_NODES, null, correlationId, false, errorMsg)
                        }
                    }
                    TYPE_REQUEST_CLICKABLE_NODES -> {
                        Log.d(TAG, "Received request_clickable_nodes (Correlation ID: $correlationId)")
                         val serviceInstance = AndroidUseAccessibilityService.instance
                        if (serviceInstance != null) {
                             // DIRECT CALL
                            serviceInstance.handleNodeRequest(ACTION_REQUEST_CLICKABLE_NODES, correlationId, null)
                        } else {
                            val errorMsg = "Client Error: Accessibility service not running or instance unavailable."
                            Log.e(TAG, "Cannot handle request_clickable_nodes: $errorMsg")
                            sendNodeListResult(TYPE_REQUEST_CLICKABLE_NODES, null, correlationId, false, errorMsg)
                        }
                    }
                    // --- END DIRECT CALL HANDLERS --- 
                    TYPE_REQUEST_LIST_PACKAGES -> {
                        val includeSystem = contentElement?.asJsonObject?.get("include_system")?.asBoolean ?: false
                        val packageNameFilter = contentElement?.asJsonObject?.get("package_name_filter")?.asString
                        Log.d(TAG, "Received request_list_packages (Include System: $includeSystem, Filter: $packageNameFilter, Correlation ID: $correlationId)")
                         val serviceInstance = AndroidUseAccessibilityService.instance
                         if (serviceInstance != null) {
                             val extras = Bundle().apply {
                                putBoolean(EXTRA_INCLUDE_SYSTEM_APPS, includeSystem)
                                putString(EXTRA_PACKAGE_NAME_FILTER, packageNameFilter)
                             }
                              // DIRECT CALL
                             serviceInstance.handleNodeRequest(ACTION_REQUEST_LIST_PACKAGES, correlationId, extras)
                         } else {
                            val errorMsg = "Client Error: Accessibility service not running or instance unavailable."
                            Log.e(TAG, "Cannot handle request_list_packages: $errorMsg")
                            sendPackagesResult(null, correlationId, false, errorMsg)
                         }
                    }
                    TYPE_CLARIFICATION_REQUEST -> {
                        val question = contentElement?.asJsonObject?.get("message")?.asString ?: "Server needs clarification."
                        listener.onClarificationRequest(question)
                    }
                    TYPE_DIALOG_RESPONSE -> {
                        // --- FIXED DIALOG HANDLING ---
                        // Dialog messages also have 'message' directly under the root
                        val dialogMessage = messageObject.get("message")?.asString ?: ""
                        listener.onDialogResponse(dialogMessage)
                        // --- END FIXED DIALOG HANDLING ---
                    }
                    // Handle other message types if needed
                    else -> {
                        Log.w(TAG, "Received unknown message type: $type")
                    }
                }
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Error parsing JSON message: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling server message: ${e.message}", e)
        }
    }
}

// Potential Improvements:
// - Error Handling: More robust error handling and reporting.
// - Reconnection Logic: Implement automatic reconnection with backoff.
// - Message Queuing: Queue messages if sending while disconnected.
// - Authentication/Security: Add mechanisms if needed.
// - Configuration: Make server URL configurable (e.g., via settings).
// - Listener Management: If multiple listeners are needed.
// - Dependency Injection: Use Hilt/Koin for managing dependencies.
