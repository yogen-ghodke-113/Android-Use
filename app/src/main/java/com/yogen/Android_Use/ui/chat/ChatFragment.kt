package com.yogen.Android_Use.ui.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yogen.Android_Use.MainActivity
import com.yogen.Android_Use.R
import com.yogen.Android_Use.databinding.FragmentChatBinding
import com.yogen.Android_Use.models.Message
import com.yogen.Android_Use.models.MessageType
import com.yogen.Android_Use.models.ChatMessage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.yogen.Android_Use.app.AndroidUseApplication

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var chatViewModel: ChatViewModel
    private lateinit var messageAdapter: MessageAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Get the application instance (which is the ViewModelStoreOwner for our app-scoped ViewModel)
        val application = requireActivity().application as AndroidUseApplication
        // Remove the factory call - ViewModelProvider will use the one from Application
        // val appViewModelFactory = application.getAppViewModelFactory()

        // Get the SINGLETON ViewModel using the Application scope
        // The application instance itself provides the correct factory via ViewModelStoreOwner
        chatViewModel = ViewModelProvider(application)[ChatViewModel::class.java]
        Log.d("ChatFragment", "Got ChatViewModel instance: $chatViewModel")

        setupRecyclerView()
        setupSendButton()
        observeViewModel()

        return root
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        binding.rvMessages.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
        }
    }

    private fun setupSendButton() {
        binding.btnSend.setOnClickListener {
            val messageText = binding.etMessage.text.toString()
            if (messageText.isNotBlank()) {
                if (chatViewModel.isConnected.value) {
                    chatViewModel.sendMessage(messageText)
                    binding.etMessage.text.clear()
                } else {
                    chatViewModel.showClientError("Cannot send: Not connected to server.")
                }
            }
        }
    }

    private fun observeViewModel() {
        Log.d("ChatFragment", "Observing messages from ViewModel: $chatViewModel")
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.messages.collectLatest { chatMessages: List<ChatMessage> ->
                if(::messageAdapter.isInitialized) {
                    val messages = chatMessages.map { chatMessage: ChatMessage ->
                        val messageType = when (chatMessage.type) {
                            MessageType.STATUS -> MessageType.STATUS
                            MessageType.USER -> MessageType.USER
                            MessageType.ERROR -> MessageType.ERROR
                            MessageType.CLARIFICATION_REQUEST -> MessageType.ASSISTANT
                            else -> MessageType.ASSISTANT
                        }
                        Message(
                            content = chatMessage.message,
                            messageType = messageType,
                            isFromUser = chatMessage.type == MessageType.USER
                        )
                    }
                    
                    messageAdapter.submitList(messages)
                    
                    if (messages.isNotEmpty()) {
                        binding.rvMessages.post {
                            binding.rvMessages.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                    binding.tvEmptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvMessages.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
                } else {
                    Log.w("ChatFragment", "Adapter not initialized when messages collected")
                }
            }
        }

        Log.d("ChatFragment", "Observing isConnected from ViewModel: $chatViewModel")
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.isConnected.collectLatest {
                updateUiForConnectionState(it)
            }
        }

        // Observe the new toast message flow
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.toastMessages.collect { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUiForConnectionState(isConnected: Boolean) {
        try {
            // Use specific tag for clarity
            Log.d("ChatFragment", "[UI_CONN_STATE] updateUiForConnectionState called with: $isConnected")
            // Optionally update button state, etc.
            // binding.btnSend.isEnabled = isConnected 
        } catch (e: IllegalStateException) {
            Log.w("ChatFragment", "Binding was null during updateUiForConnectionState")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Clean up binding
    }
} 