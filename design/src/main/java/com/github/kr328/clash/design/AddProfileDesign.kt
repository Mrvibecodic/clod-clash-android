package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.compose.screen.AddProfileAction
import com.github.kr328.clash.design.compose.screen.AddProfileScreen
import com.github.kr328.clash.design.compose.screen.AddProfileState
import com.github.kr328.clash.design.compose.screen.AddProfileStep
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AddProfileDesign(context: Context) : Design<AddProfileDesign.Request>(context) {
    sealed interface Request {
        data class Submit(val url: String, val secure: Boolean) : Request
        data object ScanQr : Request
        data object OtherWays : Request
        data object Finish : Request
    }

    private var state by mutableStateOf(AddProfileState())

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                AddProfileScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: AddProfileAction) {
        when (action) {
            is AddProfileAction.UrlChanged -> state = state.copy(url = action.url, error = null)
            is AddProfileAction.SecureChanged -> state = state.copy(secure = action.secure)
            AddProfileAction.Submit -> requests.trySend(Request.Submit(state.url, state.secure))
            AddProfileAction.ScanQr -> requests.trySend(Request.ScanQr)
            AddProfileAction.OtherWays -> requests.trySend(Request.OtherWays)
            AddProfileAction.Finish -> requests.trySend(Request.Finish)
        }
    }

    suspend fun setUrl(url: String) {
        withContext(Dispatchers.Main) {
            state = state.copy(url = url, error = null)
        }
    }

    suspend fun setFetching() {
        withContext(Dispatchers.Main) {
            state = state.copy(step = AddProfileStep.Fetching, progressText = "", progress = 0f)
        }
    }

    suspend fun setProgress(status: FetchStatus) {
        val text = when (status.action) {
            FetchStatus.Action.FetchConfiguration ->
                context.getString(R.string.format_fetching_configuration, status.args.firstOrNull().orEmpty())

            FetchStatus.Action.FetchProviders ->
                context.getString(R.string.format_fetching_provider, status.args.firstOrNull().orEmpty())

            FetchStatus.Action.Verifying -> context.getString(R.string.verifying)
            FetchStatus.Action.SubscriptionInfo -> null
        } ?: return

        val progress = if (status.max > 0) status.progress.toFloat() / status.max else 0f

        withContext(Dispatchers.Main) {
            state = state.copy(progressText = text, progress = progress)
        }
    }

    suspend fun setDone(profile: Profile, title: String) {
        withContext(Dispatchers.Main) {
            state = state.copy(step = AddProfileStep.Done, result = profile, resultTitle = title)
        }
    }

    suspend fun setError(message: String) {
        withContext(Dispatchers.Main) {
            state = state.copy(step = AddProfileStep.Input, error = message)
        }
    }
}
