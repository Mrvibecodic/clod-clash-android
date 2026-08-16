package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.design.compose.screen.AppCrashedAction
import com.github.kr328.clash.design.compose.screen.AppCrashedScreen
import com.github.kr328.clash.design.compose.screen.AppCrashedState

class AppCrashedDesign(context: Context) : Design<AppCrashedDesign.Request>(context) {
    enum class Request {
        Back,
    }

    private var state by mutableStateOf(AppCrashedState())

    override val root: View = composeRoot {
        AppCrashedScreen(state = state, onAction = ::onAction)
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
