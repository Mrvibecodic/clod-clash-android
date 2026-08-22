package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.SelectRow
import com.github.kr328.clash.design.compose.component.SwitchRow

@Immutable
data class NetworkSettingsState(
    val enableVpn: Boolean = true,
    val bypassPrivateNetwork: Boolean = true,
    val dnsHijacking: Boolean = true,
    val allowBypass: Boolean = false,
    val allowIpv6: Boolean = false,
    val systemProxy: Boolean = true,
    val systemProxySupported: Boolean = true,
    val tunStack: Int = 0,
    val editable: Boolean = true,
    val resetConnections: Boolean = true,
    val keepAwake: Boolean = false,
    val localProxyPort: Int = 0,
)

sealed interface NetworkSettingsAction {
    data object Back : NetworkSettingsAction
    data class SetEnableVpn(val enabled: Boolean) : NetworkSettingsAction
    data class SetBypassPrivateNetwork(val enabled: Boolean) : NetworkSettingsAction
    data class SetDnsHijacking(val enabled: Boolean) : NetworkSettingsAction
    data class SetAllowBypass(val enabled: Boolean) : NetworkSettingsAction
    data class SetAllowIpv6(val enabled: Boolean) : NetworkSettingsAction
    data class SetSystemProxy(val enabled: Boolean) : NetworkSettingsAction
    data class SetTunStack(val index: Int) : NetworkSettingsAction
    data class SetResetConnections(val enabled: Boolean) : NetworkSettingsAction
    data class SetKeepAwake(val enabled: Boolean) : NetworkSettingsAction
}

@Composable
fun NetworkSettingsScreen(
    state: NetworkSettingsState,
    onAction: (NetworkSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vpnOptions = state.editable && state.enableVpn

    ActivityScaffold(
        title = stringResource(R.string.network),
        onBack = { onAction(NetworkSettingsAction.Back) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!state.editable) {
                LockedNotice()
            }

            SwitchRow(
                title = stringResource(R.string.route_system_traffic),
                subtitle = if (!state.enableVpn && state.localProxyPort > 0) {
                    stringResource(
                        R.string.clod_local_proxy_summary,
                        "127.0.0.1:${state.localProxyPort}",
                    )
                } else {
                    stringResource(R.string.routing_via_vpn_service)
                },
                icon = painterResource(R.drawable.ic_baseline_vpn_lock),
                checked = state.enableVpn,
                enabled = state.editable,
                onCheckedChange = { onAction(NetworkSettingsAction.SetEnableVpn(it)) },
            )

            SectionHeader(stringResource(R.string.vpn_service_options))
            SwitchRow(
                title = stringResource(R.string.bypass_private_network),
                subtitle = stringResource(R.string.bypass_private_network_summary),
                checked = state.bypassPrivateNetwork,
                enabled = vpnOptions,
                onCheckedChange = {
                    onAction(NetworkSettingsAction.SetBypassPrivateNetwork(it))
                },
            )
            SwitchRow(
                title = stringResource(R.string.dns_hijacking),
                subtitle = stringResource(R.string.dns_hijacking_summary),
                checked = state.dnsHijacking,
                enabled = vpnOptions,
                onCheckedChange = { onAction(NetworkSettingsAction.SetDnsHijacking(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.allow_bypass),
                subtitle = stringResource(R.string.allow_bypass_summary),
                checked = state.allowBypass,
                enabled = vpnOptions,
                onCheckedChange = { onAction(NetworkSettingsAction.SetAllowBypass(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.allow_ipv6),
                subtitle = stringResource(R.string.allow_ipv6_summary),
                checked = state.allowIpv6,
                enabled = vpnOptions,
                onCheckedChange = { onAction(NetworkSettingsAction.SetAllowIpv6(it)) },
            )
            if (state.systemProxySupported) {
                SwitchRow(
                    title = stringResource(R.string.system_proxy),
                    subtitle = stringResource(R.string.system_proxy_summary),
                    checked = state.systemProxy,
                    enabled = vpnOptions,
                    onCheckedChange = { onAction(NetworkSettingsAction.SetSystemProxy(it)) },
                )
            }
            SelectRow(
                title = stringResource(R.string.tun_stack_mode),
                options = listOf(
                    stringResource(R.string.tun_stack_auto),
                    stringResource(R.string.tun_stack_system),
                    stringResource(R.string.tun_stack_gvisor),
                    stringResource(R.string.tun_stack_mixed),
                ),
                selectedIndex = state.tunStack,
                enabled = vpnOptions,
                onSelect = { onAction(NetworkSettingsAction.SetTunStack(it)) },
            )

            SectionHeader(stringResource(R.string.clod_network_switch))
            SwitchRow(
                title = stringResource(R.string.clod_reset_connections),
                subtitle = stringResource(R.string.clod_reset_connections_summary),
                checked = state.resetConnections,
                onCheckedChange = { onAction(NetworkSettingsAction.SetResetConnections(it)) },
            )

            SectionHeader(stringResource(R.string.clod_background))
            SwitchRow(
                title = stringResource(R.string.clod_keep_awake),
                subtitle = stringResource(R.string.clod_keep_awake_summary),
                checked = state.keepAwake,
                enabled = state.editable,
                onCheckedChange = { onAction(NetworkSettingsAction.SetKeepAwake(it)) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LockedNotice() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_outline_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.options_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
