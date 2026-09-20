import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.ggwavekmp.ui.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ggwave kmp sample") {
        App()
    }
}

@Preview
@Composable
fun AppDesktopPreview() {
    App()
}