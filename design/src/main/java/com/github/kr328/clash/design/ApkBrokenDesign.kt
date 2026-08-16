package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.compose.screen.ApkBrokenAction
import com.github.kr328.clash.design.compose.screen.ApkBrokenScreen

class ApkBrokenDesign(context: Context) : Design<ApkBrokenDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request

        data class OpenUrl(val url: String) : Request
    }

    override val root: View = composeRoot {
        ApkBrokenScreen(onAction = ::onAction)
    }

    private fun onAction(action: ApkBrokenAction) {
        when (action) {
            ApkBrokenAction.Back -> requests.trySend(Request.Back)
            ApkBrokenAction.OpenReleases ->
                requests.trySend(Request.OpenUrl(context.getString(R.string.meta_github_url)))
        }
    }
}
