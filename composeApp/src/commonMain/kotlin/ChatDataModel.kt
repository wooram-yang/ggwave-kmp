data class ChatDataModel(
    val messages: List<TestMessage>
) {
    data class TestMessage(
        val text: String,
        val sender: String,
        val isMine: Boolean
    )
}