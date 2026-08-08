package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
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
import com.github.kr328.clash.design.compose.component.SelectRow
import com.github.kr328.clash.design.compose.component.TextRow

sealed interface OverrideSettingsAction {
    data object Back : OverrideSettingsAction
    data object Reset : OverrideSettingsAction

    /**
     * Что-то поменялось.
     *
     * Экран правит объект настроек на месте, а не собирает новый: этот объект
     * пришёл от ядра, и активити целиком отдаёт его обратно при выходе. Копия
     * на сорок с лишним полей ради каждого нажатия ничего бы не дала, кроме
     * сорока полей кода. Действие нужно, чтобы владелец экрана пересобрал
     * состояние и Compose перерисовал строку.
     */
    data object Changed : OverrideSettingsAction
}

/**
 * @param revision счётчик правок.
 *
 * Объект настроек изменяемый, и сравнивать Compose по нему нечего: два
 * состояния с одним и тем же объектом равны, и перерисовки не будет.
 * Номер правки делает их различными — на этом вся перерисовка и держится.
 */
data class OverrideSettingsState(
    val configuration: ConfigurationOverride,
    val revision: Int = 0,
    /**
     * Провайдер запретил менять режим (`clod-lock-mode`).
     *
     * Замок обязан доезжать и сюда: строка «Режим» на этом экране пишет
     * не сессионный слот, а постоянный — то есть переживает перезапуск
     * и применяется к любой подписке. Без проверки замок с главного экрана
     * обходился бы в два нажатия и навсегда.
     */
    val modeLocked: Boolean = false,
)

/**
 * «Переопределение»: то, что накладывается поверх конфигурации подписки.
 *
 * Каждое значение трёхпозиционное: «не менять» — оставить как в подписке,
 * иначе взять наше. Раньше это был DSL `preferenceScreen` на XML с диалогом
 * на каждый пункт; списки и карты правятся текстом (см. `EditRows`).
 */
@Composable
fun OverrideSettingsScreen(
    state: OverrideSettingsState,
    onAction: (OverrideSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = state.configuration
    val changed = { onAction(OverrideSettingsAction.Changed) }

    val dontModify = stringResource(R.string.dont_modify)
    val enabled = stringResource(R.string.enabled)
    val disabled = stringResource(R.string.disabled)

    val booleans = listOf(dontModify, enabled, disabled)

    fun booleanIndex(value: Boolean?): Int = when (value) {
        null -> 0
        true -> 1
        false -> 2
    }

    fun booleanValue(index: Int): Boolean? = when (index) {
        1 -> true
        2 -> false
        else -> null
    }

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
            SelectRow(
                title = stringResource(R.string.allow_lan),
                options = booleans,
                selectedIndex = booleanIndex(configuration.allowLan),
                onSelect = { configuration.allowLan = booleanValue(it); changed() },
            )
            SelectRow(
                title = stringResource(R.string.ipv6),
                options = booleans,
                selectedIndex = booleanIndex(configuration.ipv6),
                onSelect = { configuration.ipv6 = booleanValue(it); changed() },
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
            SelectRow(
                title = stringResource(R.string.allow_private_network),
                options = booleans,
                selectedIndex = booleanIndex(configuration.externalControllerCors.allowPrivateNetwork),
                onSelect = {
                    configuration.externalControllerCors.allowPrivateNetwork = booleanValue(it)
                    changed()
                },
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

            SelectRow(
                title = stringResource(R.string.strategy),
                options = listOf(
                    dontModify,
                    stringResource(R.string.force_enable),
                    stringResource(R.string.use_built_in),
                ),
                selectedIndex = booleanIndex(configuration.dns.enable),
                onSelect = { configuration.dns.enable = booleanValue(it); changed() },
            )

            // Всё, что ниже, описывает СВОЙ DNS. Если его отключили в пользу
            // встроенного, настраивать нечего — но строки остаются видимыми,
            // чтобы человек понимал, что именно он выключил.
            val dnsEditable = configuration.dns.enable != false

            SelectRow(
                title = stringResource(R.string.prefer_h3),
                options = booleans,
                selectedIndex = booleanIndex(configuration.dns.preferH3),
                onSelect = { configuration.dns.preferH3 = booleanValue(it); changed() },
                enabled = dnsEditable,
            )
            TextRow(
                title = stringResource(R.string.listen),
                value = configuration.dns.listen,
                empty = disabled,
                onValue = { configuration.dns.listen = it; changed() },
                enabled = dnsEditable,
            )
            SelectRow(
                title = stringResource(R.string.append_system_dns),
                options = booleans,
                selectedIndex = booleanIndex(configuration.app.appendSystemDns),
                onSelect = { configuration.app.appendSystemDns = booleanValue(it); changed() },
                enabled = dnsEditable,
            )
            SelectRow(
                title = stringResource(R.string.ipv6),
                options = booleans,
                selectedIndex = booleanIndex(configuration.dns.ipv6),
                onSelect = { configuration.dns.ipv6 = booleanValue(it); changed() },
                enabled = dnsEditable,
            )
            SelectRow(
                title = stringResource(R.string.use_hosts),
                options = booleans,
                selectedIndex = booleanIndex(configuration.dns.useHosts),
                onSelect = { configuration.dns.useHosts = booleanValue(it); changed() },
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
            SelectRow(
                title = stringResource(R.string.geoip_fallback),
                options = booleans,
                selectedIndex = booleanIndex(configuration.dns.fallbackFilter.geoIp),
                onSelect = { configuration.dns.fallbackFilter.geoIp = booleanValue(it); changed() },
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
}

/**
 * Годный порт: число от 1 до 65535.
 *
 * Ноль сюда не входит намеренно — «выключено» задаётся пустым полем,
 * и показывать человеку два способа написать одно и то же незачем.
 */
private fun isPort(text: String): Boolean = text.toIntOrNull()?.let { it in 1..65535 } == true
