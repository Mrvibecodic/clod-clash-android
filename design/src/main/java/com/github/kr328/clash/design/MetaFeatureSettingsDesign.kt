package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsAction
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsScreen
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MetaFeatureSettingsDesign(
    context: Context,
    private val configuration: ConfigurationOverride,
) : Design<MetaFeatureSettingsDesign.Request>(context) {
    enum class Request {
        ResetOverride, OpenOverride, ImportGeoIp, ImportGeoSite, ImportCountry, ImportASN, Back
    }

    private var state by mutableStateOf(MetaFeatureSettingsState(configuration))

    override val root: View = composeRoot {
        MetaFeatureSettingsScreen(state = state, onAction = ::onAction)
    }

    private fun onAction(action: MetaFeatureSettingsAction) {
        when (action) {
            MetaFeatureSettingsAction.Back -> requests.trySend(Request.Back)
            MetaFeatureSettingsAction.Reset -> requests.trySend(Request.ResetOverride)
            MetaFeatureSettingsAction.ConfirmReset -> resumeReset(true)
            MetaFeatureSettingsAction.CancelReset -> resumeReset(false)
            MetaFeatureSettingsAction.Changed -> state = state.copy(revision = state.revision + 1)
            MetaFeatureSettingsAction.OpenOverride -> requests.trySend(Request.OpenOverride)
            MetaFeatureSettingsAction.ImportGeoIp -> requests.trySend(Request.ImportGeoIp)
            MetaFeatureSettingsAction.ImportGeoSite -> requests.trySend(Request.ImportGeoSite)
            MetaFeatureSettingsAction.ImportCountry -> requests.trySend(Request.ImportCountry)
            MetaFeatureSettingsAction.ImportAsn -> requests.trySend(Request.ImportASN)
        }
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
}
