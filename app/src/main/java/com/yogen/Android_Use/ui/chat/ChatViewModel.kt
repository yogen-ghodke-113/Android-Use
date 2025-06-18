package com.yogen.Android_Use.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.yogen.Android_Use.api.ApiClient
import com.yogen.Android_Use.api.ApiClientInterface
import com.yogen.Android_Use.models.ChatMessage // **ADDED IMPORT**
import com.yogen.Android_Use.models.MessageType // **ADDED IMPORT**
import com.yogen.Android_Use.utils.Constants // **ADDED IMPORT**
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers // **ADDED IMPORT**
import com.yogen.Android_Use.app.AndroidUseApplication // Import Application class
import javax.inject.Inject // Example, if using Hilt later, but principle is injection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.widget.Toast // Added for context, might be removed depending on final implementation location

// Define the Factory
class ChatViewModelFactory(
    private val application: Application,
    private val apiClient: ApiClient // Inject ApiClient
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(application, apiClient) as T // Pass apiClient to constructor
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class ChatViewModel @Inject constructor(
    private val application: Application,
    private val apiClient: ApiClient? // Receive ApiClient via constructor
) : ViewModel(), ApiClient.ConnectionListener {
    private val TAG = "ChatViewModel"

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>()
    val toastMessages = _toastMessages.asSharedFlow()

    private val isConnecting = AtomicBoolean(false)
    private var serverUrl: String? = null

    init {
        Log.d(TAG, "Initializing ChatViewModel")
        _errorMessage.value = null
        serverUrl = Constants.SERVER_URL
        // Set initial connection state based on the injected client
        _isConnected.value = apiClient?.isConnected() ?: false // Use injected apiClient
        Log.d(TAG, "ChatViewModel initialized. Server URL: $serverUrl. Initial isConnected=${_isConnected.value}")

        if (apiClient == null) {
            Log.e(TAG, "ApiClient is null during ViewModel initialization!")
            viewModelScope.launch {
                _toastMessages.emit("Internal Error: Communication client not available.")
            }
        } else {
            // The ApiClient instance should already have this as the listener in Application
            // so we don't need to set it here
            Log.d(TAG, "ChatViewModel initialized with ApiClient instance")
        }
    }

    fun attemptAutoConnect() {
        Log.d(TAG, "attemptAutoConnect called.")
        // Use injected apiClient directly
        val client = apiClient
        if (client == null) {
            Log.e(TAG, "ApiClient instance not available (was null during injection). Cannot auto-connect.")
            _errorMessage.value = "Internal error: ApiClient not ready."
            return
        }
        if (!client.isConnected() && !isConnecting.get()) {
            Log.i(TAG, "Not connected and not attempting connection. Calling connect().")
            connect()
        } else {
            Log.d(TAG, "Already connected (isConnected=${client.isConnected()}) or connection attempt in progress (isConnecting=${isConnecting.get()}).")
        }
    }

    private fun connect() {
        // Use injected apiClient directly
        val client = apiClient
        if (client == null) {
             Log.e(TAG, "Connect called but ApiClient instance not available.")
             _errorMessage.value = "Internal error: ApiClient not ready."
             isConnecting.set(false)
             return
         }
        
        if (isConnecting.compareAndSet(false, true)) {
            val url = serverUrl
            if (url.isNullOrBlank()) {
                Log.e(TAG, "Server URL is not set. Cannot connect.")
                _errorMessage.value = "Server URL not configured."
                isConnecting.set(false)
                return
            }
            Log.i(TAG, "Initiating WebSocket connection to $url...")
            try {
                client.connect(url)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during connect call: ${e.message}", e)
                _errorMessage.value = "Connection Error: ${e.message}"
                isConnecting.set(false)
                _isConnected.value = false
            }
        } else {
            Log.w(TAG, "Connection attempt already in progress.")
        }
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested.")
        // Use injected apiClient directly
        val client = apiClient
        if (client == null) {
             Log.w(TAG, "Cannot disconnect, ApiClient instance not available.")
             return
         }
        if (!client.isConnected()) {
             Log.w(TAG, "Already disconnected.")
             return
         }
        client.disconnect()
        isConnecting.set(false)
    }

    fun sendMessage(messageText: String) {
        if (messageText.isBlank()) return

        if (!_isConnected.value) {
            Log.w(TAG, "Attempted to send message while disconnected.")
            // Emit to toast flow instead of adding to chat list
            viewModelScope.launch {
                _toastMessages.emit("Cannot send: Not connected to server")
            }
            return
        }

        // Use injected apiClient directly
        val client = apiClient ?: run {
            Log.e(TAG, "Attempted to send message but ApiClient is null!")
            viewModelScope.launch {
                 _toastMessages.emit("Cannot send: Client unavailable")
            }
            return
        }

        // Add user message to UI
        val userMessage = ChatMessage(messageText, MessageType.USER)
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(userMessage)
        _messages.value = currentMessages

        // Send message via ApiClient
        viewModelScope.launch {
            try {
                client.sendInputMessage(messageText)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}", e)
                 _toastMessages.emit("Error sending message: ${e.message ?: "Unknown error"}")
            }
        }
    }

    // Backward compatibility methods
    fun onConnectionEstablished() {
        onConnected()
    }

    fun onConnectionLost(reason: String) {
        onDisconnected(reason)
    }

    fun onConnectionError(error: String) {
        onError(error)
    }

    fun addServerStatusMessage(status: String) {
         viewModelScope.launch(Dispatchers.Main) {
             addMessage(ChatMessage(message = status, type = MessageType.STATUS))
         }
    }

    fun addClarificationRequest(question: String) {
         viewModelScope.launch(Dispatchers.Main) {
             addMessage(ChatMessage(message = question, type = MessageType.CLARIFICATION_REQUEST))
         }
    }

    fun sendClarificationAnswer(answer: String) {
         if (answer.isBlank()) return
         // Use injected apiClient directly
         val client = apiClient
         if (client == null) {
              addMessage(ChatMessage(message = "Cannot send clarification, client not ready.", type = MessageType.ERROR))
              Log.e(TAG, "Attempted to send clarification but ApiClient instance not available.")
              return
          }
         if (!client.isConnected()) {
             addMessage(ChatMessage(message = "Not connected to server.", type = MessageType.ERROR))
             return
         }
         viewModelScope.launch {
             addMessage(ChatMessage(message = answer, type = MessageType.USER))
             client.sendClarificationResponse(answer)
         }
     }

    /** Adds a client-side error message to the chat list. */
    fun showClientError(errorMessage: String) {
        viewModelScope.launch(Dispatchers.Main) {
            addMessage(ChatMessage(message = errorMessage, type = MessageType.ERROR))
        }
    }

    /** Adds an assistant/server message to the chat list. */
    fun addAssistantChatMessage(messageContent: String) {
        viewModelScope.launch(Dispatchers.Main) {
            addMessage(ChatMessage(message = messageContent, type = MessageType.ASSISTANT))
        }
    }

    private fun addMessage(message: ChatMessage) {
        _messages.update { currentList: List<ChatMessage> ->
             currentList + message
         }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ChatViewModel cleared.")
        // Optional: Consider if disconnect should happen here if ViewModel is destroyed
        // disconnect() // Maybe not if ApiClient is application-scoped singleton
    }

    // --- ApiClient.ConnectionListener Implementation ---

    override fun onConnected() {
        viewModelScope.launch {
            Log.i(TAG, "[VIEWMODEL_CONN_STATE] onConnected called. Setting _isConnected=true")
            _isConnected.value = true
            isConnecting.set(false)
            // Clear any previous error message displayed in chat (if applicable)
            _errorMessage.value = null
            // Send status message to UI? Maybe redundant if UI reacts to isConnected
            addServerStatusMessage("Connected to server")
        }
    }

    override fun onDisconnected(reason: String) {
        viewModelScope.launch {
            Log.w(TAG, "[VIEWMODEL_CONN_STATE] onDisconnected called. Reason: $reason. Setting _isConnected=false")
            _isConnected.value = false
            isConnecting.set(false) // Reset connecting flag on explicit disconnect
            // Emit to toast flow
            _toastMessages.emit("Disconnected: $reason")
            // Do not add a message to the chat
        }
    }

    override fun onError(error: String) {
        viewModelScope.launch {
            Log.e(TAG, "[VIEWMODEL_CONN_STATE] onError called. Error: $error. Setting _isConnected=false")
            _isConnected.value = false
            isConnecting.set(false) // Reset connecting flag on error
            // Emit to toast flow
            _toastMessages.emit("Connection Error: $error")
            // Set error message state
            _errorMessage.value = error
            // Do not add error message to the chat
        }
    }

    override fun onStatusUpdate(message: String) {
        viewModelScope.launch {
            addServerStatusMessage(message)
        }
    }

    override fun onClarificationRequest(question: String) {
        viewModelScope.launch {
            addClarificationRequest(question)
        }
    }

    override fun onDialogResponse(message: String) {
        viewModelScope.launch {
            addAssistantChatMessage(message)
        }
    }

    companion object {
        // Moved TAG inside class
    }
}