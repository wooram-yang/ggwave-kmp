import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension

@Composable
fun ChatScreen(
    isSendingProcessing: Boolean,
    isCaptureProcessing: Boolean,
    dataModel: ChatDataModel,
    onSendClickListener: (String) -> Unit,
    onReceiveClickListener: () -> Unit,
    modifier: Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ggwaveKMP") })
        },
        content = {
            ConstraintLayout(
                modifier = modifier
            ) {
                val (firstItem, secondItem) = createRefs()

                val listState = rememberLazyListState()
                LaunchedEffect(dataModel.messages.size) {
                    listState.animateScrollToItem(dataModel.messages.size)
                }

                LazyColumn(
                    modifier = Modifier
                        .constrainAs(firstItem) {
                            top.linkTo(parent.top)
                            bottom.linkTo(secondItem.top)
                            height = Dimension.fillToConstraints
                        }
                    ,
                    contentPadding = PaddingValues(16.dp),
                    state = listState,
                    userScrollEnabled = true
                ) {
                    items(dataModel.messages) { message ->
                        ChatItem(message)
                    }
                }
                ChatBottom(
                    isSendingProcessing = isSendingProcessing,
                    isCaptureProcessing = isCaptureProcessing,
                    onSendClickListener = onSendClickListener,
                    onReceiveClickListener = onReceiveClickListener,
                    modifier = Modifier.constrainAs(secondItem) {
                        bottom.linkTo(parent.bottom)
                    }
                )
            }
        }
    )
}

@Composable
fun ChatItem(message: ChatDataModel.TestMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .align(if (message.isMine) Alignment.End else Alignment.Start)
                .clip(
                    RoundedCornerShape(
                        topStart = 48f,
                        topEnd = 48f,
                        bottomStart = if (message.isMine) 48f else 0f,
                        bottomEnd = if (message.isMine) 0f else 48f
                    )
                )
                .background(MaterialTheme.colors.primary)
                .padding(16.dp)
        ) {
            Text(text = message.text, color = Color.White)
        }
    }
}

@Composable
fun ChatBottom(
    isSendingProcessing: Boolean,
    isCaptureProcessing: Boolean,
    onSendClickListener: (String) -> Unit,
    onReceiveClickListener: () -> Unit,
    modifier: Modifier
) {
    var chatBoxString by remember { mutableStateOf(TextFieldValue("")) }
    val imageVectorForListenButton =  if (isCaptureProcessing) {
        Icons.Default.Clear
    } else {
        Icons.Default.Call
    }
    val imageVectorForSendButton =  if (isSendingProcessing) {
        Icons.Default.Close
    } else {
        Icons.Default.Send
    }

    Row(modifier = modifier.padding(16.dp)) {
        TextField(
            value = chatBoxString,
            onValueChange = { chatBoxString = it },
            modifier = Modifier
                .weight(1f)
                .padding(4.dp),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.textFieldColors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            placeholder = {
                Text(text = "Please type here")
            }
        )
        IconButton(
            onClick = onReceiveClickListener,
            modifier = Modifier
                .padding(5.dp)
                .clip(CircleShape)
                .background(color = MaterialTheme.colors.primary)
                .align(Alignment.CenterVertically)
        ) {
            Icon(
                imageVector = imageVectorForListenButton,
                tint = Color.White,
                contentDescription = "Receive",
            )
        }
        IconButton(
            enabled = isSendingProcessing.not(),
            onClick = {
                val message = chatBoxString.text
                if (message.isEmpty()) {
                    return@IconButton
                }

                onSendClickListener(chatBoxString.text)
                chatBoxString = TextFieldValue("")
            },
            modifier = Modifier
                .padding(5.dp)
                .clip(CircleShape)
                .background(color = MaterialTheme.colors.primary)
                .align(Alignment.CenterVertically)
        ) {
            Icon(
                imageVector = imageVectorForSendButton,
                tint = Color.White,
                contentDescription = "Send",
            )
        }
    }
}
