package com.github.kr328.clash.design

import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.design.compose.screen.NetworkSettingsAction
import com.github.kr328.clash.design.compose.screen.NetworkSettingsScreen
import com.github.kr328.clash.design.compose.screen.NetworkSettingsState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore

class NetworkSettingsDesign(
    context: Context,
    private val uiStore: UiStore,
    private val srvStore: ServiceStore,
    running: Boolean,
) : Design<NetworkSettingsDesign.Request>(context) {
    sealed interface Request {
        data object StartAccessControlList : Request
        data object Back : Request
    }

    /**
     * Порядок важен: экран отдаёт индекс, а в хранилище лежит строка.
     * Значения те же, что понимает ядро.
     */
    private val tunStacks = listOf("system", "gvisor", "mixed")

    private val accessControlModes = AccessControlMode.entries

    private var state by mutableStateOf(
        NetworkSettingsState(
            enableVpn = uiStore.enableVpn,
            bypassPrivateNetwork = srvStore.bypassPrivateNetwork,
            dnsHijacking = srvStore.dnsHijacking,
            allowBypass = srvStore.allowBypass,
            allowIpv6 = srvStore.allowIpv6,
            systemProxy = srvStore.systemProxy,
            // Системный прокси через VpnService появился в Android 10.
            systemProxySupported = Build.VERSION.SDK_INT >= 29,
            tunStack = tunStacks.indexOf(srvStore.tunStackMode).coerceAtLeast(0),
            accessControlMode = accessControlModes
                .indexOf(srvStore.accessControlMode)
                .coerceAtLeast(0),
            editable = !running,
            resetConnections = srvStore.resetConnectionsOnNetworkChange,
        ),
    )

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                NetworkSettingsScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: NetworkSettingsAction) {
        when (action) {
            NetworkSettingsAction.Back -> requests.trySend(Request.Back)
            NetworkSettingsAction.OpenAccessControlList ->
                requests.trySend(Request.StartAccessControlList)

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
            is NetworkSettingsAction.SetSystemProxy -> {
                srvStore.systemProxy = action.enabled

                state = state.copy(systemProxy = action.enabled)
            }
            is NetworkSettingsAction.SetTunStack -> {
                val stack = tunStacks.getOrNull(action.index) ?: return

                srvStore.tunStackMode = stack

                state = state.copy(tunStack = action.index)
            }
            is NetworkSettingsAction.SetAccessControlMode -> {
                val mode = accessControlModes.getOrNull(action.index) ?: return

                srvStore.accessControlMode = mode

                state = state.copy(accessControlMode = action.index)
            }
        }
    }
}
