import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ggwavekmp.CoreManagerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

interface PlaySoundListener {
    fun onPlayEnded()
}

interface CaptureSoundListener {
    fun onReceivedMessage(value: String)
}

class AppViewModel: ViewModel(), PlaySoundListener, CaptureSoundListener {
    private val coreManager = CoreManagerFactory.createInstance()

    private val _messages = MutableStateFlow(listOf<ChatDataModel.TestMessage>())
    val messages: StateFlow<List<ChatDataModel.TestMessage>> = _messages
    private var messageWillBeSent = ""

    private var _isSendingProcessing = MutableStateFlow(false)
    val isSendingProcessing: StateFlow<Boolean> = _isSendingProcessing

    private var _isCaptureProcessing = MutableStateFlow(false)
    val isCaptureProcessing: StateFlow<Boolean> = _isCaptureProcessing

    init {
        coreManager.playSoundListener = this
        coreManager.captureSoundListener = this
        coreManager.messageWillBeSent = messageWillBeSent
    }

    fun sendMessage(message: String) {
        viewModelScope.launch {
            _messages.emit(_messages.value + ChatDataModel.TestMessage(message, "You", true))
            coreManager.messageWillBeSent = message

            startPlaySound()
        }
    }

    private fun receiveMessage(message: String) {
        viewModelScope.launch {
            _messages.emit(_messages.value + ChatDataModel.TestMessage(message, "Someone", false))
        }
    }

    fun startCaptureSound() {
        _isCaptureProcessing.value = true
        coreManager.startCapturing()
    }

    fun stopCaptureSound() {
        _isCaptureProcessing.value = false
        coreManager.stopCapturing()
    }

    private fun startPlaySound() {
        _isSendingProcessing.value = true
        coreManager.startPlayback()
    }

    fun stopPlaySound() {
        _isSendingProcessing.value = false
        coreManager.stopPlayback()
    }

    override fun onPlayEnded() {
        _isSendingProcessing.value = false
    }

    override fun onReceivedMessage(value: String) {
        receiveMessage(value)
    }
}