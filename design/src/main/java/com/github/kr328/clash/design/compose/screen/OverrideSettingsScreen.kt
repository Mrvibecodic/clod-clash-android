package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.LinesRow
import com.github.kr328.clash.design.compose.component.PairsRow
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.ResetConfirmDialog
import com.github.kr328.clash.design.compose.component.SelectRow
import com.github.kr328.clash.design.compose.component.TextRow
import com.github.kr328.clash.design.compose.component.TriStateRow
import com.github.kr328.clash.design.compose.component.UnreadableSettingsCard

sealed interface OverrideSettingsAction {
    data object Back : OverrideSettingsAction
    data object Reset : OverrideSettingsAction
    data object ConfirmReset : OverrideSettingsAction
    data object CancelReset : OverrideSettingsAction

    data object Changed : OverrideSettingsAction
}

@Immutable
data class OverrideSettingsState(
    val configuration: ConfigurationOverride,
    val revision: Int = 0,
    val modeLocked: Boolean = false,
    val modeShadow: ModeShadow? = null,
    val confirmingReset: Boolean = false,
    val unreadable: String? = null,
)

@Immutable
data class ModeShadow(val profile: String, val mode: TunnelState.Mode)

@Composable
fun OverrideSettingsScreen(
    state: OverrideSettingsState,
    onAction: (OverrideSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = state.configuration
    val changed = { onAction(OverrideSettingsAction.Changed) }

    val dontModify = stringResource(R.string.dont_modify)
    val disabled = stringResource(R.string.disabled)

    ActivityScaffold(
        title = stringResource(R.string.override),
        onBack = { onAction(OverrideSettingsAction.Back) },
        modifier = modifier,
        actions = {
            IconButton(onClick = { onAction(OverrideSettingsAction.Reset) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_replay),
                    contentDescription = stringResource(R.string.reset_override_settings),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val unreadable = state.unreadable

            if (unreadable != null) {
                UnreadableSettingsCard(
                    detail = unreadable,
                    onReset = { onAction(OverrideSettingsAction.Reset) },
                    modifier = Modifier.padding(16.dp),
                )

                return@Column
            }

            SectionHeader(stringResource(R.string.general))

            TextRow(
                title = stringResource(R.string.http_port),
                value = configuration.httpPort?.let { if (it == 0) "" else it.toString() },
                empty = disabled,
                numeric = true,
                valid = ::isPort,
                onValue = { configuration.httpPort = it?.let { v -> v.toIntOrNull() ?: 0 }; changed() },
            )
            TextRow(
                title = stringResource(R.string.socks_port),
                value = configuration.socksPort?.let { if (it == 0) "" else it.toString() },
                empty = disabled,
                numeric = true,
                valid = ::isPort,
                onValue = { configuration.socksPort = it?.let { v -> v.toIntOrNull() ?: 0 }; changed() },
            )
            TextRow(
                title = stringResource(R.string.redirect_port),
                value = configuration.redirectPort?.let { if (it == 0) "" else it.toString() },
                empty = disabled,
                numeric = true,
                valid = ::isPort,
                onValue = { configuration.redirectPort = it?.let { v -> v.toIntOrNull() ?: 0 }; changed() },
            )
            TextRow(
                title = stringResource(R.string.tproxy_port),
                value = configuration.tproxyPort?.let { if (it == 0) "" else it.toString() },
                empty = disabled,
                numeric = true,
                valid = ::isPort,
                onValue = { configuration.tproxyPort = it?.let { v -> v.toIntOrNull() ?: 0 }; changed() },
            )
            TextRow(
                title = stringResource(R.string.mixed_port),
                value = configuration.mixedPort?.let { if (it == 0) "" else it.toString() },
                empty = disabled,
                numeric = true,
                valid = ::isPort,
                onValue = { configuration.mixedPort = it?.let { v -> v.toIntOrNull() ?: 0 }; changed() },
            )
            LinesRow(
                title = stringResource(R.string.authentication),
                values = configuration.authentication,
                onValues = { configuration.authentication = it; changed() },
            )
            TriStateRow(
                title = stringResource(R.string.allow_lan),
                value = configuration.allowLan,
                onValue = { configuration.allowLan = it; changed() },
            )
            TriStateRow(
                title = stringResource(R.string.ipv6),
                value = configuration.ipv6,
                onValue = { configuration.ipv6 = it; changed() },
            )
            TextRow(
                title = stringResource(R.string.bind_address),
                value = configuration.bindAddress,
                empty = stringResource(R.string.default_),
                onValue = { configuration.bindAddress = it; changed() },
            )
            TextRow(
                title = stringResource(R.string.external_controller),
                value = configuration.externalController,
                empty = stringResource(R.string.default_),
                onValue = { configuration.externalController = it; changed() },
            )
            TextRow(
                title = stringResource(R.string.external_controller_tls),
                value = configuration.externalControllerTLS,
                empty = stringResource(R.string.default_),
                onValue = { configuration.externalControllerTLS = it; changed() },
            )
            LinesRow(
                title = stringResource(R.string.allow_origins),
                values = configuration.externalControllerCors.allowOrigins,
                onValues = { configuration.externalControllerCors.allowOrigins = it; changed() },
            )
            TriStateRow(
                title = stringResource(R.string.allow_private_network),
                value = configuration.externalControllerCors.allowPrivateNetwork,
                onValue = { configuration.externalControllerCors.allowPrivateNetwork = it; changed() },
            )
            TextRow(
                title = stringResource(R.string.secret),
                value = configuration.secret,
                empty = stringResource(R.string.default_),
                onValue = { configuration.secret = it; changed() },
            )
            if (!state.modeLocked) {
                SelectRow(
                    title = stringResource(R.string.mode),
                    options = listOf(
                        dontModify,
                        stringResource(R.string.direct_mode),
                        stringResource(R.string.global_mode),
                        stringResource(R.string.rule_mode),
                    ),
                    selectedIndex = when (configuration.mode) {
                        null -> 0
                        TunnelState.Mode.Direct -> 1
                        TunnelState.Mode.Global -> 2
                        TunnelState.Mode.Rule -> 3
                        else -> 0
                    },
                    onSelect = {
                        configuration.mode = when (it) {
                            1 -> TunnelState.Mode.Direct
                            2 -> TunnelState.Mode.Global
                            3 -> TunnelState.Mode.Rule
                            else -> null
                        }
                        changed()
                    },
                )
                state.modeShadow?.let { shadow ->
                    Text(
                        text = stringResource(
                            R.string.clod_override_mode_shadowed,
                            shadow.profile,
                            modeLabel(shadow.mode),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                    )
                }
            }
            SelectRow(
                title = stringResource(R.string.log_level),
                options = listOf(
                    dontModify,
                    stringResource(R.string.info),
                    stringResource(R.string.warning),
                    stringResource(R.string.error),
                    stringResource(R.string.debug),
                    stringResource(R.string.silent),
                ),
                selectedIndex = when (configuration.logLevel) {
                    null -> 0
                    LogMessage.Level.Info -> 1
                    LogMessage.Level.Warning -> 2
                    LogMessage.Level.Error -> 3
                    LogMessage.Level.Debug -> 4
                    LogMessage.Level.Silent -> 5
                    else -> 0
                },
                onSelect = {
                    configuration.logLevel = when (it) {
                        1 -> LogMessage.Level.Info
                        2 -> LogMessage.Level.Warning
                        3 -> LogMessage.Level.Error
                        4 -> LogMessage.Level.Debug
                        5 -> LogMessage.Level.Silent
                        else -> null
                    }
                    changed()
                },
            )
            PairsRow(
                title = stringResource(R.string.hosts),
                values = configuration.hosts,
                onValues = { configuration.hosts = it; changed() },
            )

            SectionHeader(stringResource(R.string.dns))

            TriStateRow(
                title = stringResource(R.string.strategy),
                value = configuration.dns.enable,
                onValue = { configuration.dns.enable = it; changed() },
                options = listOf(
                    dontModify,
                    stringResource(R.string.force_enable),
                    stringResource(R.string.use_built_in),
                ),
            )

            val dnsEditable = configuration.dns.enable != false

            TriStateRow(
                title = stringResource(R.string.prefer_h3),
                value = configuration.dns.preferH3,
                onValue = { configuration.dns.preferH3 = it; changed() },
                enabled = dnsEditable,
            )
            TextRow(
                title = stringResource(R.string.listen),
                value = configuration.dns.listen,
                empty = disabled,
                onValue = { configuration.dns.listen = it; changed() },
                enabled = dnsEditable,
            )
            TriStateRow(
                title = stringResource(R.string.append_system_dns),
                value = configuration.app.appendSystemDns,
                onValue = { configuration.app.appendSystemDns = it; changed() },
                enabled = dnsEditable,
            )
            TriStateRow(
                title = stringResource(R.string.ipv6),
                value = configuration.dns.ipv6,
                onValue = { configuration.dns.ipv6 = it; changed() },
                enabled = dnsEditable,
            )
            TriStateRow(
                title = stringResource(R.string.use_hosts),
                value = configuration.dns.useHosts,
                onValue = { configuration.dns.useHosts = it; changed() },
                enabled = dnsEditable,
            )
            SelectRow(
                title = stringResource(R.string.enhanced_mode),
                options = listOf(
                    dontModify,
                    disabled,
                    stringResource(R.string.fakeip),
                    stringResource(R.string.mapping),
                ),
                selectedIndex = when (configuration.dns.enhancedMode) {
                    null -> 0
                    ConfigurationOverride.DnsEnhancedMode.None -> 1
                    ConfigurationOverride.DnsEnhancedMode.FakeIp -> 2
                    ConfigurationOverride.DnsEnhancedMode.Mapping -> 3
                },
                onSelect = {
                    configuration.dns.enhancedMode = when (it) {
                        1 -> ConfigurationOverride.DnsEnhancedMode.None
                        2 -> ConfigurationOverride.DnsEnhancedMode.FakeIp
                        3 -> ConfigurationOverride.DnsEnhancedMode.Mapping
                        else -> null
                    }
                    changed()
                },
                enabled = dnsEditable,
            )
            LinesRow(
                title = stringResource(R.string.name_server),
                values = configuration.dns.nameServer,
                onValues = { configuration.dns.nameServer = it; changed() },
                enabled = dnsEditable,
            )
            LinesRow(
                title = stringResource(R.string.fallback),
                values = configuration.dns.fallback,
                onValues = { configuration.dns.fallback = it; changed() },
                enabled = dnsEditable,
            )
            LinesRow(
                title = stringResource(R.string.default_name_server),
                values = configuration.dns.defaultServer,
                onValues = { configuration.dns.defaultServer = it; changed() },
                enabled = dnsEditable,
            )
            LinesRow(
                title = stringResource(R.string.fakeip_filter),
                values = configuration.dns.fakeIpFilter,
                onValues = { configuration.dns.fakeIpFilter = it; changed() },
                enabled = dnsEditable,
            )
            SelectRow(
                title = stringResource(R.string.fakeip_filter_mode),
                options = listOf(
                    dontModify,
                    stringResource(R.string.blacklist),
                    stringResource(R.string.whitelist),
                    stringResource(R.string.rule),
                ),
                selectedIndex = when (configuration.dns.fakeIPFilterMode) {
                    null -> 0
                    ConfigurationOverride.FilterMode.BlackList -> 1
                    ConfigurationOverride.FilterMode.WhiteList -> 2
                    ConfigurationOverride.FilterMode.Rule -> 3
                },
                onSelect = {
                    configuration.dns.fakeIPFilterMode = when (it) {
                        1 -> ConfigurationOverride.FilterMode.BlackList
                        2 -> ConfigurationOverride.FilterMode.WhiteList
                        3 -> ConfigurationOverride.FilterMode.Rule
                        else -> null
                    }
                    changed()
                },
                enabled = dnsEditable,
            )
            TriStateRow(
                title = stringResource(R.string.geoip_fallback),
                value = configuration.dns.fallbackFilter.geoIp,
                onValue = { configuration.dns.fallbackFilter.geoIp = it; changed() },
                enabled = dnsEditable,
            )
            TextRow(
                title = stringResource(R.string.geoip_fallback_code),
                value = configuration.dns.fallbackFilter.geoIpCode,
                empty = stringResource(R.string.raw_cn),
                onValue = { configuration.dns.fallbackFilter.geoIpCode = it; changed() },
                enabled = dnsEditable,
            )
            LinesRow(
                title = stringResource(R.string.domain_fallback),
                values = configuration.dns.fallbackFilter.domain,
                onValues = { configuration.dns.fallbackFilter.domain = it; changed() },
                enabled = dnsEditable,
            )
            LinesRow(
                title = stringResource(R.string.ipcidr_fallback),
                values = configuration.dns.fallbackFilter.ipcidr,
                onValues = { configuration.dns.fallbackFilter.ipcidr = it; changed() },
                enabled = dnsEditable,
            )
            PairsRow(
                title = stringResource(R.string.name_server_policy),
                values = configuration.dns.nameserverPolicy,
                onValues = { configuration.dns.nameserverPolicy = it; changed() },
                enabled = dnsEditable,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.confirmingReset) {
        ResetConfirmDialog(
            onConfirm = { onAction(OverrideSettingsAction.ConfirmReset) },
            onDismiss = { onAction(OverrideSettingsAction.CancelReset) },
        )
    }
}

private fun isPort(text: String): Boolean = text.toIntOrNull()?.let { it in 0..65535 } == true
