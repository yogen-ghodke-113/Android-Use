package com.yogen.Android_Use.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yogen.Android_Use.R
import com.yogen.Android_Use.models.Message
import com.yogen.Android_Use.models.MessageType
import android.util.Log

class MessageAdapter : ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)
        // Log.d("MessageAdapter", "Binding position $position: Type=${message.messageType}, FromUser=${message.isFromUser}, Content='${message.content.take(50)}...'")
        holder.bind(message)
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvUserMessage: TextView = itemView.findViewById(R.id.tvUserMessage)
        private val tvAssistantMessage: TextView = itemView.findViewById(R.id.tvAssistantMessage)
        private val ivAssistantAvatar: ImageView = itemView.findViewById(R.id.ivAssistantAvatar)

        fun bind(message: Message) {
            // Log.d("MessageAdapterViewHolder", "Binding message ID ${message.id}. isFromUser=${message.isFromUser}")
            if (message.isFromUser) {
                // User message - show user bubble, hide assistant elements
                tvUserMessage.text = message.content
                tvUserMessage.visibility = View.VISIBLE
                tvAssistantMessage.visibility = View.GONE
                ivAssistantAvatar.visibility = View.GONE
                // Reset assistant text style if needed
                tvAssistantMessage.setTypeface(null, android.graphics.Typeface.NORMAL)
            } else {
                // Log.d("MessageAdapterViewHolder", "Binding non-user message. Type: ${message.messageType}")
                // Assistant/Status/Error message
                tvAssistantMessage.text = message.content
                tvAssistantMessage.visibility = View.VISIBLE
                ivAssistantAvatar.visibility = View.VISIBLE // Show avatar for assistant/error too?
                tvUserMessage.visibility = View.GONE

                // Set text color and style based on message type
                val context = itemView.context
                var typefaceStyle = android.graphics.Typeface.NORMAL
                val colorRes = when (message.messageType) {
                    MessageType.STATUS -> {
                        typefaceStyle = android.graphics.Typeface.ITALIC
                        android.R.color.darker_gray
                    }
                    MessageType.ERROR -> android.R.color.holo_red_dark
                    MessageType.CLARIFICATION_REQUEST -> R.color.default_assistant_text_color // Treat same as assistant
                    MessageType.ASSISTANT -> R.color.default_assistant_text_color
                    else -> R.color.default_assistant_text_color // Default for USER, COMMAND, etc.
                }
                tvAssistantMessage.setTextColor(ContextCompat.getColor(context, colorRes))
                tvAssistantMessage.setTypeface(tvAssistantMessage.typeface, typefaceStyle)
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }
} 