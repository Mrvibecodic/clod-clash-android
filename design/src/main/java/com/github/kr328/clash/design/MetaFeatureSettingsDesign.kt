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
import com.github.kr328.clash.design.util.Confirmation

class MetaFeatureSettingsDesign(
    context: Context,
    private val configuration: ConfigurationOverride,
    unreadable: String? = null,
) : Design<MetaFeatureSettingsDesign.Request>(context) {
    enum class Request {
        ResetOverride, OpenOverride, ImportGeoIp, ImportGeoSite, ImportASN, Back
    }

    private var state by mutableStateOf(MetaFeatureSettingsState(configuration, unreadable = unreadable))

    override val root: View = composeRoot {
        MetaFeatureSettingsScreen(state = state, onAction = ::onAction)
    }

    private fun onAction(action: MetaFeatureSettingsAction) {
        when (action) {
            MetaFeatureSettingsAction.Back -> requests.trySend(Request.Back)
            MetaFeatureSettingsAction.Reset -> requests.trySend(Request.ResetOverride)
            MetaFeatureSettingsAction.ConfirmReset -> resetConfirmation.resume(true)
            MetaFeatureSettingsAction.CancelReset -> resetConfirmation.resume(false)
            MetaFeatureSettingsAction.Changed -> state = state.copy(revision = state.revision + 1)
            MetaFeatureSettingsAction.OpenOverride -> requests.trySend(Request.OpenOverride)
            MetaFeatureSettingsAction.ImportGeoIp -> requests.trySend(Request.ImportGeoIp)
            MetaFeatureSettingsAction.ImportGeoSite -> requests.trySend(Request.ImportGeoSite)
            MetaFeatureSettingsAction.ImportAsn -> requests.trySend(Request.ImportASN)
        }
    }

    private val resetConfirmation = Confirmation { state = state.copy(confirmingReset = it) }

    suspend fun requestResetConfirm(): Boolean = resetConfirmation.request()
}
