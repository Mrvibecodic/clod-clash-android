package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsAction
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsScreen
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsState
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint
import com.github.kr328.clash.service.util.sendDiagnosticsChanged

class DiagnosticsSettingsDesign(
    context: Context,
    private val srvStore: ServiceStore,
    vpnServiceRunning: Boolean,
) : Design<DiagnosticsSettingsDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request
        data object Saved : Request
    }

    private val credentials = DiagnosticsCredentialStore(context)

    private var state by mutableStateOf(
        DiagnosticsSettingsState(
            diagnosticsConfigured = credentials.read() != null,
            diagnosticsEndpoint = normalizeDiagnosticsEndpoint(srvStore.diagnosticsEndpoint).orEmpty(),
            vpnServiceRunning = vpnServiceRunning,
        ),
    )

    override val root: View = composeRoot {
        DiagnosticsSettingsScreen(state = state, onAction = ::onAction)
    }

    private fun onAction(action: DiagnosticsSettingsAction) {
        when (action) {
            DiagnosticsSettingsAction.Back -> requests.trySend(Request.Back)
            is DiagnosticsSettingsAction.SaveDiagnosticsCredential -> {
                if (state.vpnServiceRunning) return
                val endpoint = normalizeDiagnosticsEndpoint(action.endpoint) ?: return
                val replacesCredentials = action.username.isNotBlank() ||
                    action.password.isNotBlank() ||
                    action.controllerSecret.isNotBlank() ||
                    action.remotePort >= 0
                if (!replacesCredentials && !state.diagnosticsConfigured) return

                val replacement = if (replacesCredentials) {
                    DiagnosticsCredential.create(
                        action.username,
                        action.password,
                        action.controllerSecret,
                        action.remotePort,
                    ) ?: return
                } else {
                    null
                }

                val saved = replacement == null || credentials.save(replacement)
                if (!saved) {
                    context.sendDiagnosticsChanged(DiagnosticsMode.DISABLED)
                    return
                }

                srvStore.diagnosticsEndpoint = endpoint
                state = state.copy(
                    diagnosticsConfigured = credentials.read() != null,
                    diagnosticsEndpoint = endpoint,
                )
                context.sendDiagnosticsChanged(DiagnosticsMode.DISABLED)
                requests.trySend(Request.Saved)
            }
            DiagnosticsSettingsAction.ClearDiagnosticsCredential -> {
                if (state.vpnServiceRunning) return
                credentials.clear()
                state = state.copy(diagnosticsConfigured = false)
                context.sendDiagnosticsChanged(DiagnosticsMode.DISABLED)
            }
        }
    }
}
