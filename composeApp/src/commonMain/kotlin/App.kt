
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel


@Composable
fun App(
    viewModel: AppViewModel = viewModel { AppViewModel() }
) {
    val isSendingProcessing by viewModel.isSendingProcessing.collectAsState()
    val isCaptureProcessing by viewModel.isCaptureProcessing.collectAsState()

    val messages by viewModel.messages.collectAsState()

    MaterialTheme {
        ChatScreen(
            isSendingProcessing = isSendingProcessing,
            isCaptureProcessing = isCaptureProcessing,
            dataModel = ChatDataModel(messages = messages),
            onSendClickListener = { message ->
                if (isCaptureProcessing) {
                    viewModel.stopCaptureSound()
                }

                if (message.isNotEmpty() && isSendingProcessing.not()) {
                    viewModel.sendMessage(message)
                }
            },
            onReceiveClickListener = {
                if (isCaptureProcessing) {
                    viewModel.stopCaptureSound()
                } else {
                    viewModel.startCaptureSound()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}