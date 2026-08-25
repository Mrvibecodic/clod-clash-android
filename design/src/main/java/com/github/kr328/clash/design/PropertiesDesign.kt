package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.compose.screen.FetchProgress
import com.github.kr328.clash.design.compose.screen.PropertiesAction
import com.github.kr328.clash.design.compose.screen.PropertiesScreen
import com.github.kr328.clash.design.compose.screen.PropertiesState
import com.github.kr328.clash.design.util.ValidatorAutoUpdateInterval
import com.github.kr328.clash.design.util.ValidatorHttpUrl
import com.github.kr328.clash.design.util.ValidatorNotBlank
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PropertiesDesign(context: Context) : Design<PropertiesDesign.Request>(context) {
    sealed interface Request {
        data object Commit : Request
        data object BrowseFiles : Request
        data object Back : Request
    }

    private var state by mutableStateOf(PropertiesState())

    private var base: Profile? = null

    override val root: View = composeRoot {
        PropertiesScreen(state = state, onAction = ::onAction)
    }

    var profile: Profile
        get() = checkNotNull(base) { "profile is not set" }.copy(
            name = state.name,
            source = state.url,
            interval = TimeUnit.MINUTES.toMillis(state.intervalMinutes.toLongOrNull() ?: 0),
        )
        set(value) {
            base = value

            val minutes = TimeUnit.MILLISECONDS.toMinutes(value.interval)

            state = state.copy(
                name = value.name,
                url = value.source,
                intervalMinutes = if (minutes == 0L) "" else minutes.toString(),
                urlEditable = value.type == Profile.Type.Url,
                intervalEditable = value.type != Profile.Type.File,
            )
        }

    val progressing: Boolean
        get() = state.processing != null

    val draftValid: Boolean
        get() = ValidatorNotBlank(state.name) &&
            (!state.urlEditable || ValidatorHttpUrl(state.url)) &&
            ValidatorAutoUpdateInterval(state.intervalMinutes)

    private fun onAction(action: PropertiesAction) {
        when (action) {
            PropertiesAction.Back -> request(Request.Back)
            PropertiesAction.Commit -> request(Request.Commit)
            PropertiesAction.BrowseFiles -> request(Request.BrowseFiles)
            is PropertiesAction.NameChanged -> state = state.copy(name = action.value)
            is PropertiesAction.UrlChanged -> state = state.copy(url = action.value)
            is PropertiesAction.IntervalChanged ->
                state = state.copy(intervalMinutes = action.value.filter { it.isDigit() })

            PropertiesAction.ConfirmExit -> resumeExit(true)
            PropertiesAction.CancelExit -> resumeExit(false)
        }
    }

    suspend fun withProcessing(executeTask: suspend (suspend (FetchStatus) -> Unit) -> Unit) {
        try {
            setProgress(FetchProgress(context.getString(R.string.initializing)))

            executeTask { setProgress(it.toProgress()) }
        } finally {
            setProgress(null)
        }
    }

    private suspend fun setProgress(progress: FetchProgress?) {
        withContext(Dispatchers.Main) {
            state = state.copy(processing = progress)
        }
    }

    private var exitConfirmation: CancellableContinuation<Boolean>? = null

    suspend fun requestExitWithoutSaving(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                exitConfirmation = continuation

                state = state.copy(confirmingExit = true)

                continuation.invokeOnCancellation {
                    exitConfirmation = null

                    state = state.copy(confirmingExit = false)
                }
            }
        }
    }

    private fun resumeExit(confirmed: Boolean) {
        state = state.copy(confirmingExit = false)

        val continuation = exitConfirmation ?: return

        exitConfirmation = null

        if (continuation.isActive) {
            continuation.resumeWith(Result.success(confirmed))
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    private fun FetchStatus.toProgress(): FetchProgress = when (action) {
        FetchStatus.Action.FetchConfiguration -> FetchProgress(
            text = context.getString(R.string.format_fetching_configuration, args[0]),
        )
        FetchStatus.Action.FetchProviders -> FetchProgress(
            text = context.getString(R.string.format_fetching_provider, args[0]),
            progress = fraction(),
        )
        FetchStatus.Action.Verifying -> FetchProgress(
            text = context.getString(R.string.verifying),
            progress = fraction(),
        )
        FetchStatus.Action.SubscriptionInfo -> state.processing
            ?: FetchProgress(context.getString(R.string.initializing))
    }

    private fun FetchStatus.fraction(): Float =
        if (max > 0) progress.toFloat() / max else -1f
}
