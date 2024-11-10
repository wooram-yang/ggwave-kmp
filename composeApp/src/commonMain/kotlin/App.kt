
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.materialkolor.rememberDynamicColorScheme


@Composable
fun App(
    viewModel: AppViewModel = viewModel { AppViewModel() }
) {
    val isSendingProcessing by viewModel.isSendingProcessing.collectAsState()
    val isCaptureProcessing by viewModel.isCaptureProcessing.collectAsState()

    val messages by viewModel.messages.collectAsState()

    MyTheme(
        seedColor = Color.Cyan
    ) {
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

@Composable
fun MyTheme(
    seedColor: Color,
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = rememberDynamicColorScheme(seedColor, useDarkTheme, false)

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}