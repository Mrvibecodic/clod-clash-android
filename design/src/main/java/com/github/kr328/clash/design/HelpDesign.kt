package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.compose.screen.HelpAction
import com.github.kr328.clash.design.compose.screen.HelpScreen

class HelpDesign(context: Context) : Design<HelpDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request
        data class OpenUrl(val url: String) : Request
    }

    override val root: View = composeRoot {
        HelpScreen(onAction = ::onAction)
    }

    private fun onAction(action: HelpAction) {
        when (action) {
            HelpAction.Back -> requests.trySend(Request.Back)
            is HelpAction.OpenUrl -> requests.trySend(Request.OpenUrl(action.url))
        }
    }
}
