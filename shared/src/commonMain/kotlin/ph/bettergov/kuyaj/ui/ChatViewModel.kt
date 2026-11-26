package ph.bettergov.kuyaj.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.bettergov.kuyaj.app.KuyaServices
import ph.bettergov.kuyaj.chat.Reply

/** A photo the person attached: a preview to show, and the JPEG bytes the model reads. */
class Attachment(val preview: ImageBitmap, val jpeg: ByteArray)

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val photo: ImageBitmap? = null,
    val reply: Reply? = null,
    val streaming: Boolean = false,
)

class ChatViewModel(val services: KuyaServices) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _attachment = MutableStateFlow<Attachment?>(null)
    val attachment: StateFlow<Attachment?> = _attachment

    private var nextId = 0L

    fun attach(photo: Attachment) {
        _attachment.value = photo
    }

    fun clearAttachment() {
        _attachment.value = null
    }

    fun newChat() {
        services.orchestrator.clear()
        _messages.value = emptyList()
    }

    fun send(text: String) {
        val photo = _attachment.value
        if (_busy.value || (text.isBlank() && photo == null)) return
        _attachment.value = null
        _busy.value = true
        val botId = nextId + 1
        _messages.update {
            it + ChatMessage(nextId, true, text.trim(), photo?.preview) + ChatMessage(botId, false, "", streaming = true)
        }
        nextId += 2
        viewModelScope.launch {
            val reply = runCatching {
                services.orchestrator.reply(text, photo?.jpeg) { delta ->
                    _messages.update { list -> list.map { if (it.id == botId) it.copy(text = it.text + delta) else it } }
                }
            }.getOrElse { Reply("Something went wrong: ${it.message}", Reply.Kind.SYSTEM) }
            // The final reply replaces streamed text, which matters when the grounding check withheld it.
            _messages.update { list -> list.map { if (it.id == botId) it.copy(text = reply.text, reply = reply, streaming = false) else it } }
            _busy.value = false
        }
    }
}
