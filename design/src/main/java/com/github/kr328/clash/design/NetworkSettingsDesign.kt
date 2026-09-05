package com.github.kr328.clash.design

import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.design.compose.screen.NetworkSettingsAction
import com.github.kr328.clash.design.compose.screen.NetworkSettingsScreen
import com.github.kr328.clash.design.compose.screen.NetworkSettingsState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.resolveTunStack

class NetworkSettingsDesign(
    context: Context,
    private val uiStore: UiStore,
    private val srvStore: ServiceStore,
    prefs: NetworkSettingsPrefs,
    running: Boolean,
    localProxyPort: Int,
    private val profileTunStack: String,
    privateDnsHost: String?,
) : Design<NetworkSettingsDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request
    }

    private val tunStacks = listOf("auto", "system", "gvisor", "mixed")

    private var state by mutableStateOf(
        NetworkSettingsState(
            enableVpn = uiStore.enableVpn,
            bypassPrivateNetwork = prefs.bypassPrivateNetwork,
            dnsHijacking = prefs.dnsHijacking,
            allowBypass = prefs.allowBypass,
            allowIpv6 = prefs.allowIpv6,
            systemProxy = prefs.systemProxy,
            systemProxySupported = Build.VERSION.SDK_INT >= 29,
            tunStack = tunStacks.indexOf(prefs.tunStackMode).coerceAtLeast(0),
            editable = !running,
            resetConnections = prefs.resetConnections,
            keepAwake = prefs.keepAwake,
            localProxyPort = localProxyPort,
            effectiveTunStack = resolveTunStack(prefs.tunStackMode, profileTunStack),
            effectiveTunStackFromProfile = tunStackFromProfile(prefs.tunStackMode),
            privateDnsHost = privateDnsHost,
        ),
    )

    private fun tunStackFromProfile(mode: String): Boolean =
        resolveTunStack(mode, "") != resolveTunStack(mode, profileTunStack)

    override val root: View = composeRoot {
        NetworkSettingsScreen(state = state, onAction = ::onAction)
    }

    private fun onAction(action: NetworkSettingsAction) {
        when (action) {
            NetworkSettingsAction.Back -> requests.trySend(Request.Back)
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

                state = state.copy(
                    tunStack = action.index,
                    effectiveTunStack = resolveTunStack(stack, profileTunStack),
                    effectiveTunStackFromProfile = tunStackFromProfile(stack),
                )
            }
        }
    }
}
