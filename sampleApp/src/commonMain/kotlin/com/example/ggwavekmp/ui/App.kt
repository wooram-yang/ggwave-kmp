package com.example.ggwavekmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ggwavekmp.ui.chat.ChatScreen
import com.example.ggwavekmp.ui.chat.ChatViewModel
import com.example.ggwavekmp.ui.theme.AppTheme

@Composable
fun App(
    viewModel: ChatViewModel = viewModel { ChatViewModel() },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppTheme {
        ChatScreen(
            uiState = uiState,
            onInputChange = viewModel::onInputChange,
            onSendClick = viewModel::onSendClick,
            onToggleCapture = viewModel::onToggleCapture,
        )
    }
}
