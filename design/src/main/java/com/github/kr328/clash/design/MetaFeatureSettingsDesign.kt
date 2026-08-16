package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsAction
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsScreen
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MetaFeatureSettingsDesign(
    context: Context,
    private val configuration: ConfigurationOverride,
) : Design<MetaFeatureSettingsDesign.Request>(context) {
    enum class Request {
        ResetOverride, OpenOverride, ImportGeoIp, ImportGeoSite, ImportCountry, ImportASN, Back
    }

    private var state by mutableStateOf(MetaFeatureSettingsState(configuration))

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                MetaFeatureSettingsScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: MetaFeatureSettingsAction) {
        when (action) {
            MetaFeatureSettingsAction.Back -> requests.trySend(Request.Back)
            MetaFeatureSettingsAction.Reset -> requests.trySend(Request.ResetOverride)
            MetaFeatureSettingsAction.Changed -> state = state.copy(revision = state.revision + 1)
            MetaFeatureSettingsAction.OpenOverride -> requests.trySend(Request.OpenOverride)
            MetaFeatureSettingsAction.ImportGeoIp -> requests.trySend(Request.ImportGeoIp)
            MetaFeatureSettingsAction.ImportGeoSite -> requests.trySend(Request.ImportGeoSite)
            MetaFeatureSettingsAction.ImportCountry -> requests.trySend(Request.ImportCountry)
            MetaFeatureSettingsAction.ImportAsn -> requests.trySend(Request.ImportASN)
        }
    }

    suspend fun requestResetConfirm(): Boolean {
        return suspendCancellableCoroutine { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.reset_override_settings)
                .setMessage(R.string.reset_override_settings_message)
                .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()

            dialog.setOnDismissListener {
                if (!ctx.isCompleted) {
                    ctx.resume(false)
                }
            }

            ctx.invokeOnCancellation {
                dialog.dismiss()
            }
        }
    }
}
