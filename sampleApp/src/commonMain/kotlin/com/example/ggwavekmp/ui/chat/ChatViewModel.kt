package com.example.ggwavekmp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.moreflow.ggwave.GgwaveSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val session: GgwaveSession = GgwaveSession.create(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var nextMessageId = 0L

    init {
        viewModelScope.launch {
            session.receivedMessages.collect { message ->
                onMessageReceived(message)
            }
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun onSendClick() {
        val state = _uiState.value
        val message = state.inputText.trim()
        if (message.isEmpty() || state.isSending) return

        if (state.isCapturing) {
            stopCapture()
        }

        val outgoing = ChatMessage(
            id = nextMessageId++,
            text = message,
            sender = "You",
            isMine = true,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + outgoing,
                inputText = "",
                isSending = true,
            )
        }

        viewModelScope.launch {
            try {
                session.send(message)
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun onToggleCapture() {
        if (_uiState.value.isCapturing) {
            stopCapture()
        } else {
            startCapture()
        }
    }

    override fun onCleared() {
        session.close()
    }

    private fun startCapture() {
        try {
            session.startCapture()
            _uiState.update { it.copy(isCapturing = true) }
        } catch (_: Exception) {
            _uiState.update { it.copy(isCapturing = false) }
        }
    }

    private fun stopCapture() {
        session.stopCapture()
        _uiState.update { it.copy(isCapturing = false) }
    }

    private fun onMessageReceived(message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            val incoming = ChatMessage(
                id = nextMessageId++,
                text = message,
                sender = "Someone",
                isMine = false,
            )
            _uiState.update { it.copy(messages = it.messages + incoming) }
        }
    }
}
