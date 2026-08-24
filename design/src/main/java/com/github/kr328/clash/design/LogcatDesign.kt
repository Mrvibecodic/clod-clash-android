package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.compose.screen.LogcatAction
import com.github.kr328.clash.design.compose.screen.LogcatScreen
import com.github.kr328.clash.design.compose.screen.LogcatState
import com.github.kr328.clash.design.ui.ToastDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatDesign(
    context: Context,
    streaming: Boolean,
) : Design<LogcatDesign.Request>(context) {
    enum class Request {
        Back, Close, Delete, Export
    }

    private var state by mutableStateOf(LogcatState(streaming = streaming))

    override val root: View = composeRoot {
        LogcatScreen(state = state, onAction = ::onAction)
    }

    private fun onAction(action: LogcatAction) {
        when (action) {
            LogcatAction.Back -> requests.trySend(Request.Back)
            LogcatAction.Close -> requests.trySend(Request.Close)
            LogcatAction.Delete -> requests.trySend(Request.Delete)
            LogcatAction.Export -> requests.trySend(Request.Export)
            is LogcatAction.Copy -> launch {
                val data = ClipData.newPlainText("log_message", action.message.message)

                context.getSystemService<ClipboardManager>()?.setPrimaryClip(data)

                showToast(R.string.copied, ToastDuration.Short)
            }
        }
    }

    suspend fun patchMessages(messages: List<LogMessage>, removed: Int) {
        withContext(Dispatchers.Main) {
            state = state.copy(
                messages = messages,
                firstLine = state.firstLine + removed,
            )
        }
    }
}
