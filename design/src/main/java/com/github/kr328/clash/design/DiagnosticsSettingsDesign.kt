package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.common.model.DiagnosticsLogEvent
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsAction
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsScreen
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsState
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint
import com.github.kr328.clash.service.util.DiagnosticsEventJournal
import com.github.kr328.clash.service.util.sendDiagnosticsChanged
import com.github.kr328.clash.service.util.sendDiagnosticsLogEvent

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
    private val diagnosticsEvents = DiagnosticsEventJournal(context)

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
            DiagnosticsSettingsAction.Back -> {
                recordSettingsEvent(DiagnosticsLogEvent.SettingsClosed)
                requests.trySend(Request.Back)
            }
            is DiagnosticsSettingsAction.SaveDiagnosticsCredential -> {
                recordSettingsEvent(DiagnosticsLogEvent.SettingsSaveRequested)
                if (state.vpnServiceRunning) {
                    recordSettingsEvent(DiagnosticsLogEvent.SettingsSaveRejectedVpnRunning)
                    return
                }
                val endpoint = normalizeDiagnosticsEndpoint(action.endpoint) ?: run {
                    recordSettingsEvent(DiagnosticsLogEvent.SettingsSaveRejectedEndpointInvalid)
                    return
                }
                val replacesCredentials = action.username.isNotBlank() ||
                    action.password.isNotBlank() ||
                    action.controllerSecret.isNotBlank() ||
                    action.remotePort >= 0
                if (!replacesCredentials && !state.diagnosticsConfigured) {
                    recordSettingsEvent(DiagnosticsLogEvent.SettingsSaveRejectedCredentialMissing)
                    return
                }

                val replacement = if (replacesCredentials) {
                    DiagnosticsCredential.create(
                        action.username,
                        action.password,
                        action.controllerSecret,
                        action.remotePort,
                    ) ?: run {
                        recordSettingsEvent(DiagnosticsLogEvent.SettingsSaveRejectedCredentialIncomplete)
                        return
                    }
                } else {
                    null
                }

                val saved = replacement == null || credentials.save(replacement)
                if (!saved) {
                    recordSettingsEvent(DiagnosticsLogEvent.CredentialSaveFailed)
                    context.sendDiagnosticsChanged(DiagnosticsMode.DISABLED)
                    return
                }

                if (replacement != null) {
                    recordSettingsEvent(DiagnosticsLogEvent.CredentialSaveSucceeded)
                } else {
                    recordSettingsEvent(DiagnosticsLogEvent.CredentialPreserved)
                }

                srvStore.diagnosticsEndpoint = endpoint
                if (srvStore.diagnosticsEndpoint != endpoint) {
                    recordSettingsEvent(DiagnosticsLogEvent.EndpointSaveFailed)
                    context.sendDiagnosticsChanged(DiagnosticsMode.DISABLED)
                    return
                }
                recordSettingsEvent(DiagnosticsLogEvent.EndpointSaveSucceeded)
                state = state.copy(
                    diagnosticsConfigured = credentials.read() != null,
                    diagnosticsEndpoint = endpoint,
                )
                context.sendDiagnosticsChanged(DiagnosticsMode.DISABLED)
                requests.trySend(Request.Saved)
            }
            DiagnosticsSettingsAction.ClearDiagnosticsCredential -> {
                recordSettingsEvent(DiagnosticsLogEvent.CredentialDeleteRequested)
                if (state.vpnServiceRunning) {
                    recordSettingsEvent(DiagnosticsLogEvent.CredentialDeleteRejectedVpnRunning)
                    return
                }
                if (!credentials.clear()) {
                    recordSettingsEvent(DiagnosticsLogEvent.CredentialDeleteFailed)
                    return
                }
                recordSettingsEvent(DiagnosticsLogEvent.CredentialDeleteSucceeded)
                state = state.copy(diagnosticsConfigured = false)
                context.sendDiagnosticsChanged(DiagnosticsMode.DISABLED)
            }
        }
    }

    private fun recordSettingsEvent(event: DiagnosticsLogEvent) {
        if (state.vpnServiceRunning) context.sendDiagnosticsLogEvent(event)
        else diagnosticsEvents.append(event)
    }
}
