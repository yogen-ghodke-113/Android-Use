package com.yogen.Android_Use.app

import android.app.Application
import android.util.Log
// import com.yogen.Android_Use.MainActivity // Remove if not used
import com.yogen.Android_Use.api.ApiClient
import com.yogen.Android_Use.execution.ExecutionEngine
import com.yogen.Android_Use.ui.chat.ChatViewModel
import com.yogen.Android_Use.ui.chat.ChatViewModelFactory // Import the custom factory
// import java.lang.ref.WeakReference // Remove if not used
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Custom Application class to initialize and hold singleton instances.
 */
class AndroidUseApplication : Application(), ViewModelStoreOwner {

    companion object {
        private const val TAG = "AndroidUseApp"
    }

    // --- NEW: Application Coroutine Scope ---
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // --- END NEW ---

    private val appViewModelStore: ViewModelStore by lazy {
        ViewModelStore()
    }

    // Lazy initialization of singletons
    // Note: Consider Dependency Injection frameworks (Hilt, Koin) for larger apps

    // ViewModelStoreOwner implementation
    override val viewModelStore: ViewModelStore
        get() = appViewModelStore

    // --- Corrected Initialization Order ---

    // 1. Execution Engine (needs ApiClient set later)
    val executionEngine: ExecutionEngine by lazy {
        Log.d(TAG, "Initializing ExecutionEngine.")
        ExecutionEngine(applicationContext, null) // ApiClient set later
    }

    // 2. Connection Listener (references chatViewModel lazily)
    private val connectionListener: ApiClient.ConnectionListener by lazy {
        Log.d(TAG, "Creating ConnectionListener.")
        object : ApiClient.ConnectionListener {
            override fun onConnected() {
                Log.i(TAG, "Listener: Connected! Forwarding to ChatViewModel.")
                chatViewModel.onConnectionEstablished()
            }
            override fun onDisconnected(reason: String) {
                Log.i(TAG, "Listener: Disconnected ($reason). Forwarding.")
                chatViewModel.onConnectionLost(reason)
            }
            override fun onError(error: String) {
                Log.e(TAG, "Listener: Error ($error). Forwarding.")
                chatViewModel.onConnectionError(error)
            }
            override fun onStatusUpdate(message: String) {
                Log.d(TAG, "Listener: Status Update. Forwarding.")
                chatViewModel.addServerStatusMessage(message)
            }
            override fun onClarificationRequest(question: String) {
                Log.d(TAG, "Listener: Clarification Request. Forwarding.")
                chatViewModel.addClarificationRequest(question)
            }
            override fun onDialogResponse(message: String) { // Implemented
                Log.d(TAG, "Listener: Dialog Response. Forwarding.")
                chatViewModel.addAssistantChatMessage(message)
            }
        }
    }

    // 3. ApiClient (depends on listener and executionEngine)
    val apiClient: ApiClient by lazy {
        Log.d(TAG, "Initializing ApiClient.")
        // Initialize with listener and engine
        val client = ApiClient.initialize(
            applicationContext,
            connectionListener, // Pass the listener instance
            executionEngine     // Pass the engine instance
            // No ChatViewModel needed here anymore
        )
        // Set the ApiClient in ExecutionEngine *after* client creation
        Log.d(TAG, "Setting ApiClient in ExecutionEngine instance.")
        executionEngine.setApiClient(client)
        client
    }

    // 4. ViewModel Factory (depends on apiClient)
    private val appViewModelFactory: ChatViewModelFactory by lazy {
        Log.d(TAG, "Creating ChatViewModelFactory.")
        ChatViewModelFactory(this, apiClient) // Pass initialized apiClient
    }

    // 5. ChatViewModel (depends on factory)
    val chatViewModel: ChatViewModel by lazy {
        Log.d(TAG, "Initializing ChatViewModel.")
        ViewModelProvider(this, appViewModelFactory)[ChatViewModel::class.java]
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application onCreate")
        // No default factory needed here anymore
        // appViewModelFactory = ViewModelProvider.AndroidViewModelFactory.getInstance(this)

        // Eagerly initialize singletons in the correct order
        Log.d(TAG, "Triggering lazy init...")
        executionEngine // Initialize 1st
        // Listener is lazy, only created when ApiClient needs it
        apiClient       // Initialize 2nd (triggers listener init)
        // Factory is lazy, only created when ChatViewModel needs it
        chatViewModel   // Initialize 3rd (triggers factory init)
        Log.d(TAG, "Application onCreate finished.")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "Application onTerminate")
        // Clean up resources if necessary
        apiClient.disconnect() // Ensure disconnect on terminate
    }
} 