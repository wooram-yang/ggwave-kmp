package com.example.ggwavekmp.ui.chat

import io.github.moreflow.ggwave.GgwaveProtocol

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
    val errorMessage: String? = null,
    val captureLevel: Float = 0f,
    val protocol: GgwaveProtocol = GgwaveProtocol.AudibleFast,
) {
    val canSend: Boolean
        get() = inputText.isNotBlank() && !isSending
}
