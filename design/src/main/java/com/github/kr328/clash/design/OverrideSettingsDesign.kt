package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.compose.screen.ModeShadow
import com.github.kr328.clash.design.compose.screen.OverrideSettingsAction
import com.github.kr328.clash.design.compose.screen.OverrideSettingsScreen
import com.github.kr328.clash.design.compose.screen.OverrideSettingsState
import com.github.kr328.clash.design.util.Confirmation

class OverrideSettingsDesign(
    context: Context,
    private val configuration: ConfigurationOverride,
    modeLocked: Boolean = false,
    modeShadow: ModeShadow? = null,
    unreadable: String? = null,
) : Design<OverrideSettingsDesign.Request>(context) {
    sealed interface Request {
        data object ResetOverride : Request
        data object Back : Request
    }

    private var state by mutableStateOf(
        OverrideSettingsState(
            configuration,
            modeLocked = modeLocked,
            modeShadow = modeShadow,
            unreadable = unreadable,
        ),
    )

    override val root: View = composeRoot {
        OverrideSettingsScreen(state = state, onAction = ::onAction)
    }

    private val resetConfirmation = Confirmation { state = state.copy(confirmingReset = it) }

    suspend fun requestResetConfirm(): Boolean = resetConfirmation.request()

    private fun onAction(action: OverrideSettingsAction) {
        when (action) {
            OverrideSettingsAction.Back -> requests.trySend(Request.Back)
            OverrideSettingsAction.Reset -> requests.trySend(Request.ResetOverride)
            OverrideSettingsAction.ConfirmReset -> resetConfirmation.resume(true)
            OverrideSettingsAction.CancelReset -> resetConfirmation.resume(false)
            OverrideSettingsAction.Changed -> state = state.copy(revision = state.revision + 1)
        }
    }
}
