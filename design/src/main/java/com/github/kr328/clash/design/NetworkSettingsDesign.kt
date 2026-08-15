package com.github.kr328.clash.design

import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.design.compose.screen.NetworkSettingsAction
import com.github.kr328.clash.design.compose.screen.NetworkSettingsScreen
import com.github.kr328.clash.design.compose.screen.NetworkSettingsState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint
import com.github.kr328.clash.service.util.sendDiagnosticsChanged

class NetworkSettingsDesign(
    context: Context,
    private val uiStore: UiStore,
    private val srvStore: ServiceStore,
    running: Boolean,
    localProxyPort: Int,
    diagnosticsState: DiagnosticsState,
) : Design<NetworkSettingsDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request
        data object OpenDiagnostics : Request
    }

    private val tunStacks = listOf("auto", "system", "gvisor", "mixed")
    private val credentials = DiagnosticsCredentialStore(context)

    private var state by mutableStateOf(
        NetworkSettingsState(
            enableVpn = uiStore.enableVpn,
            bypassPrivateNetwork = srvStore.bypassPrivateNetwork,
            dnsHijacking = srvStore.dnsHijacking,
            allowBypass = srvStore.allowBypass,
            allowIpv6 = srvStore.allowIpv6,
            systemProxy = srvStore.systemProxy,
            systemProxySupported = Build.VERSION.SDK_INT >= 29,
            tunStack = tunStacks.indexOf(srvStore.tunStackMode).coerceAtLeast(0),
            editable = !running,
            resetConnections = srvStore.resetConnectionsOnNetworkChange,
            keepAwake = srvStore.keepAwake,
            localProxyPort = localProxyPort,
            diagnosticsEnabled = diagnosticsState != DiagnosticsState.STOPPED,
            diagnosticsConfigured = credentials.read() != null,
            diagnosticsEndpoint = normalizeDiagnosticsEndpoint(srvStore.diagnosticsEndpoint).orEmpty(),
            vpnServiceRunning = running && uiStore.enableVpn,
            diagnosticsState = diagnosticsState,
        ),
    )

    override val root: View = composeRoot {
        NetworkSettingsScreen(state = state, onAction = ::onAction)
    }

    private fun onAction(action: NetworkSettingsAction) {
        when (action) {
            NetworkSettingsAction.Back -> requests.trySend(Request.Back)
            NetworkSettingsAction.OpenDiagnostics -> requests.trySend(Request.OpenDiagnostics)
            NetworkSettingsAction.EnableDiagnostics -> {
                if (
                    !state.diagnosticsConfigured ||
                        state.diagnosticsEndpoint.isBlank() ||
                        !state.vpnServiceRunning
                ) return

                if (credentials.read() == null) return
                state = state.copy(diagnosticsEnabled = true)
                context.sendDiagnosticsChanged(DiagnosticsMode.ENABLED)
            }
            NetworkSettingsAction.DisableDiagnostics -> {
                state = state.copy(diagnosticsEnabled = false)
                context.sendDiagnosticsChanged(DiagnosticsMode.DISABLED)
            }
            is NetworkSettingsAction.SetEnableVpn -> {
                uiStore.enableVpn = action.enabled

                state = state.copy(enableVpn = action.enabled)
            }
            is NetworkSettingsAction.SetBypassPrivateNetwork -> {
                srvStore.bypassPrivateNetwork = action.enabled

                state = state.copy(bypassPrivateNetwork = action.enabled)
            }
            is NetworkSettingsAction.SetDnsHijacking -> {
                srvStore.dnsHijacking = action.enabled

                state = state.copy(dnsHijacking = action.enabled)
            }
            is NetworkSettingsAction.SetAllowBypass -> {
                srvStore.allowBypass = action.enabled

                state = state.copy(allowBypass = action.enabled)
            }
            is NetworkSettingsAction.SetAllowIpv6 -> {
                srvStore.allowIpv6 = action.enabled

                state = state.copy(allowIpv6 = action.enabled)
            }
            is NetworkSettingsAction.SetResetConnections -> {
                srvStore.resetConnectionsOnNetworkChange = action.enabled

                state = state.copy(resetConnections = action.enabled)
            }
            is NetworkSettingsAction.SetKeepAwake -> {
                srvStore.keepAwake = action.enabled

                state = state.copy(keepAwake = action.enabled)
            }
            is NetworkSettingsAction.SetSystemProxy -> {
                srvStore.systemProxy = action.enabled

                state = state.copy(systemProxy = action.enabled)
            }
            is NetworkSettingsAction.SetTunStack -> {
                val stack = tunStacks.getOrNull(action.index) ?: return

                srvStore.tunStackMode = stack

                state = state.copy(tunStack = action.index)
            }
        }
    }

    fun updateDiagnosticsStatus(status: DiagnosticsState) {
        state = state.copy(
            diagnosticsEnabled = status != DiagnosticsState.STOPPED,
            diagnosticsState = status,
        )
    }

    fun refreshDiagnosticsAccess() {
        state = state.copy(
            diagnosticsConfigured = credentials.read() != null,
            diagnosticsEndpoint = normalizeDiagnosticsEndpoint(srvStore.diagnosticsEndpoint).orEmpty(),
        )
    }
}
