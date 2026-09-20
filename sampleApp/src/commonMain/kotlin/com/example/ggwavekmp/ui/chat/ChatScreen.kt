package com.example.ggwavekmp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ggwavekmp.sampleapp.generated.resources.Res
import ggwavekmp.sampleapp.generated.resources.app_name
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onToggleCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.app_name),
                        color = Color.White,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = uiState.inputText,
                isSending = uiState.isSending,
                isCapturing = uiState.isCapturing,
                canSend = uiState.canSend,
                onInputChange = onInputChange,
                onSendClick = onSendClick,
                onToggleCapture = onToggleCapture,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            state = listState,
        ) {
            items(
                items = uiState.messages,
                key = { it.id },
            ) { message ->
                ChatItem(message)
            }
        }
    }
}

@Composable
private fun ChatItem(message: ChatMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .align(if (message.isMine) Alignment.End else Alignment.Start)
                .clip(
                    RoundedCornerShape(
                        topStart = 48f,
                        topEnd = 48f,
                        bottomStart = if (message.isMine) 48f else 0f,
                        bottomEnd = if (message.isMine) 0f else 48f,
                    ),
                )
                .background(color = MaterialTheme.colorScheme.primary)
                .padding(16.dp),
        ) {
            Text(text = message.text, color = Color.White)
        }
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    isSending: Boolean,
    isCapturing: Boolean,
    canSend: Boolean,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onToggleCapture: () -> Unit,
) {
    val listenIcon = if (isCapturing) Icons.Default.Clear else Icons.Default.Call
    val sendIcon = if (isSending) Icons.Default.Close else Icons.AutoMirrored.Default.Send

    Row(modifier = Modifier.padding(16.dp)) {
        TextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(4.dp),
            enabled = !isSending,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            placeholder = {
                Text(text = "Please type here")
            },
        )
        IconButton(
            onClick = onToggleCapture,
            modifier = Modifier
                .padding(5.dp)
                .clip(CircleShape)
                .background(color = MaterialTheme.colorScheme.primary)
                .align(Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = listenIcon,
                tint = Color.White,
                contentDescription = "Receive",
            )
        }
        IconButton(
            enabled = canSend,
            onClick = onSendClick,
            modifier = Modifier
                .padding(5.dp)
                .clip(CircleShape)
                .background(color = MaterialTheme.colorScheme.primary)
                .align(Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = sendIcon,
                tint = Color.White,
                contentDescription = "Send",
            )
        }
    }
}
