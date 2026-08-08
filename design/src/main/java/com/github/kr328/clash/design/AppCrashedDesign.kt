package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.design.compose.screen.AppCrashedAction
import com.github.kr328.clash.design.compose.screen.AppCrashedScreen
import com.github.kr328.clash.design.compose.screen.AppCrashedState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme

class AppCrashedDesign(context: Context) : Design<AppCrashedDesign.Request>(context) {
    enum class Request {
        Back,
    }

    private var state by mutableStateOf(AppCrashedState())

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                AppCrashedScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: AppCrashedAction) {
        when (action) {
            AppCrashedAction.Back -> requests.trySend(Request.Back)
        }
    }

    fun setAppLogs(logs: String) {
        state = state.copy(logs = logs)
    }
}
