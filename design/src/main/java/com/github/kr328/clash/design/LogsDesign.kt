package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.design.compose.screen.LogsAction
import com.github.kr328.clash.design.compose.screen.LogsScreen
import com.github.kr328.clash.design.compose.screen.LogsState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.model.LogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogsDesign(context: Context) : Design<LogsDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request
        data object StartLogcat : Request

        /** Приходит уже после подтверждения: спрашивает сам экран. */
        data object DeleteAll : Request

        data class OpenFile(val file: LogFile) : Request
    }

    private var state by mutableStateOf(LogsState())

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                LogsScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: LogsAction) {
        when (action) {
            LogsAction.Back -> request(Request.Back)
            LogsAction.StartLogcat -> request(Request.StartLogcat)
            LogsAction.RequestDeleteAll -> state = state.copy(confirmingDelete = true)
            LogsAction.CancelDeleteAll -> state = state.copy(confirmingDelete = false)
            LogsAction.ConfirmDeleteAll -> {
                state = state.copy(confirmingDelete = false)

                request(Request.DeleteAll)
            }
            is LogsAction.OpenFile -> request(Request.OpenFile(action.file))
        }
    }

    suspend fun patchLogs(logs: List<LogFile>) {
        withContext(Dispatchers.Main) {
            // Свежие сверху. Раньше порядок задавала файловая система, то есть
            // был произвольным, и нужный лог приходилось искать глазами.
            state = state.copy(files = logs.sortedByDescending { it.date }, loaded = true)
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
