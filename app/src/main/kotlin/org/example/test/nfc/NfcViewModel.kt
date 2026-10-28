package org.example.test.nfc

import android.nfc.NdefMessage
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class NfcAvailability { Unknown, Unsupported, Disabled, Ready }

enum class PendingAction { None, Write, Erase }

data class StatusMessage(val text: String, val isError: Boolean, val id: Long = System.nanoTime())

class NfcViewModel : ViewModel() {

    var availability by mutableStateOf(NfcAvailability.Unknown)

    /** The scan currently shown on the Read tab. */
    var latest by mutableStateOf<ScanResult?>(null)
    val history = mutableStateListOf<ScanResult>()

    /** Records the user is composing on the Write tab. */
    val draft = mutableStateListOf<DraftRecord>()

    var pending by mutableStateOf(PendingAction.None)
        private set

    var status by mutableStateOf<StatusMessage?>(null)
        private set

    @Volatile
    private var pendingMessage: NdefMessage? = null

    @Volatile
    private var pendingAction = PendingAction.None

    private val main = Handler(Looper.getMainLooper())

    /** Rough NDEF payload size of the draft, for the size meter. */
    fun draftBytes(): Int =
        runCatching { draft.toList().toMessageOrNull()?.toByteArray()?.size ?: 0 }.getOrDefault(0)

    fun armWrite() {
        val msg = draft.toList().toMessageOrNull() ?: return
        pendingMessage = msg
        pendingAction = PendingAction.Write
        pending = PendingAction.Write
    }

    fun armErase() {
        pendingMessage = emptyMessage()
        pendingAction = PendingAction.Erase
        pending = PendingAction.Erase
    }

    fun cancelPending() {
        pendingMessage = null
        pendingAction = PendingAction.None
        pending = PendingAction.None
    }

    fun show(result: ScanResult) {
        latest = result
    }

    /** Called from the NFC reader thread. Does the tag I/O here, then posts UI state to main. */
    fun onTag(tag: Tag) {
        val action = pendingAction
        val message = pendingMessage

        if (action == PendingAction.None || message == null) {
            val scan = readTag(tag)
            main.post {
                latest = scan
                history.add(0, scan)
                while (history.size > 20) history.removeAt(history.lastIndex)
            }
            return
        }

        val error = writeMessage(tag, message)
        val verify = if (error == null) runCatching { readTag(tag) }.getOrNull() else null
        main.post {
            if (error == null) {
                status = StatusMessage(
                    if (action == PendingAction.Erase) "Tag erased." else "Written to tag.",
                    isError = false,
                )
                verify?.let {
                    latest = it
                    history.add(0, it)
                }
                cancelPending()
            } else {
                // Keep the dialog open so the user can just retry with another tag.
                status = StatusMessage(error, isError = true)
            }
        }
    }
}
