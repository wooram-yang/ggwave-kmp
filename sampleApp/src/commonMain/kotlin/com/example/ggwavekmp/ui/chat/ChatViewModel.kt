package com.example.ggwavekmp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.moreflow.ggwave.GgwaveProtocol
import io.github.moreflow.ggwave.GgwaveSession
import kotlinx.coroutines.CancellationException
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
        viewModelScope.launch {
            session.isCapturing.collect { isCapturing ->
                _uiState.update { it.copy(isCapturing = isCapturing) }
            }
        }
        viewModelScope.launch {
            session.captureErrors.collect { error ->
                _uiState.update { it.copy(errorMessage = error.message ?: "Microphone capture failed") }
            }
        }
        viewModelScope.launch {
            session.captureLevel.collect { level ->
                _uiState.update { it.copy(captureLevel = level) }
            }
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun onProtocolChange(protocol: GgwaveProtocol) {
        _uiState.update { it.copy(protocol = protocol) }
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
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            try {
                session.send(message, state.protocol)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.message ?: "Failed to send message") }
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
        _uiState.update { it.copy(errorMessage = null) }
        try {
            session.startCapture()
            _uiState.update { it.copy(isCapturing = true) }
        } catch (error: Exception) {
            _uiState.update {
                it.copy(isCapturing = false, errorMessage = error.message ?: "Microphone capture failed")
            }
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
