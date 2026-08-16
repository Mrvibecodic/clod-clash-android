package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.compose.screen.OverrideSettingsAction
import com.github.kr328.clash.design.compose.screen.OverrideSettingsScreen
import com.github.kr328.clash.design.compose.screen.OverrideSettingsState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class OverrideSettingsDesign(
    context: Context,
    private val configuration: ConfigurationOverride,
    modeLocked: Boolean = false,
) : Design<OverrideSettingsDesign.Request>(context) {
    sealed interface Request {
        data object ResetOverride : Request
        data object Back : Request
    }

    private var state by mutableStateOf(
        OverrideSettingsState(configuration, modeLocked = modeLocked),
    )

    override val root: View = composeRoot {
        OverrideSettingsScreen(state = state, onAction = ::onAction)
    }

    private var resetConfirmation: CancellableContinuation<Boolean>? = null

    suspend fun requestResetConfirm(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                resetConfirmation = continuation

                state = state.copy(confirmingReset = true)

                continuation.invokeOnCancellation {
                    resetConfirmation = null

                    state = state.copy(confirmingReset = false)
                }
            }
        }
    }

    private fun resumeReset(confirmed: Boolean) {
        state = state.copy(confirmingReset = false)

        val continuation = resetConfirmation ?: return

        resetConfirmation = null

        if (continuation.isActive) {
            continuation.resumeWith(Result.success(confirmed))
        }
    }

    private fun onAction(action: OverrideSettingsAction) {
        when (action) {
            OverrideSettingsAction.Back -> requests.trySend(Request.Back)
            OverrideSettingsAction.Reset -> requests.trySend(Request.ResetOverride)
            OverrideSettingsAction.ConfirmReset -> resumeReset(true)
            OverrideSettingsAction.CancelReset -> resumeReset(false)
            OverrideSettingsAction.Changed -> state = state.copy(revision = state.revision + 1)
        }
    }
}
