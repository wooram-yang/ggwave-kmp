package com.example.ggwavekmp.ui.chat

data class ChatMessage(
    val id: Long,
    val text: String,
    val sender: String,
    val isMine: Boolean,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isCapturing: Boolean = false,
) {
    val canSend: Boolean
        get() = inputText.isNotBlank() && !isSending
}
