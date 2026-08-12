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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.LinesRow
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.SelectRow

sealed interface MetaFeatureSettingsAction {
    data object Back : MetaFeatureSettingsAction
    data object Reset : MetaFeatureSettingsAction

    /** См. [OverrideSettingsAction.Changed] — здесь ровно та же механика. */
    data object Changed : MetaFeatureSettingsAction

    /** Ключи age: генерация и проверка живут в ядре, окно поднимает активити. */
    data class OpenAgeKeys(val hybrid: Boolean) : MetaFeatureSettingsAction

    data object ImportGeoIp : MetaFeatureSettingsAction
    data object ImportGeoSite : MetaFeatureSettingsAction
    data object ImportCountry : MetaFeatureSettingsAction
    data object ImportAsn : MetaFeatureSettingsAction
}

/** @param revision см. [OverrideSettingsState]. */
@Immutable
data class MetaFeatureSettingsState(
    val configuration: ConfigurationOverride,
    val revision: Int = 0,
)

/**
 * «Функции Meta»: то, что умеет само ядро сверх обычной конфигурации.
 *
 * Sniffer определяет протокол по первым байтам соединения и подменяет адрес
 * назначения именем узла — без него правила по доменам не работают там,
 * где приложение ходит сразу по IP.
 */
@Composable
fun MetaFeatureSettingsScreen(
    state: MetaFeatureSettingsState,
    onAction: (MetaFeatureSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = state.configuration
    val changed = { onAction(MetaFeatureSettingsAction.Changed) }

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
        title = stringResource(R.string.meta_features),
        onBack = { onAction(MetaFeatureSettingsAction.Back) },
        modifier = modifier,
        actions = {
            IconButton(onClick = { onAction(MetaFeatureSettingsAction.Reset) }) {
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
            SectionHeader(stringResource(R.string.age_key_category))

            ActionRow(
                title = stringResource(R.string.age_key_type_x25519),
                icon = painterResource(R.drawable.ic_baseline_key),
                subtitle = stringResource(R.string.age_key_generate_summary),
                onClick = { onAction(MetaFeatureSettingsAction.OpenAgeKeys(hybrid = false)) },
            )
            ActionRow(
                title = stringResource(R.string.age_key_type_hybrid),
                icon = painterResource(R.drawable.ic_baseline_key),
                subtitle = stringResource(R.string.age_key_generate_summary),
                onClick = { onAction(MetaFeatureSettingsAction.OpenAgeKeys(hybrid = true)) },
            )

            SectionHeader(stringResource(R.string.settings))

            SelectRow(
                title = stringResource(R.string.unified_delay),
                options = booleans,
                selectedIndex = booleanIndex(configuration.unifiedDelay),
                onSelect = { configuration.unifiedDelay = booleanValue(it); changed() },
            )
            SelectRow(
                title = stringResource(R.string.geodata_mode),
                options = booleans,
                selectedIndex = booleanIndex(configuration.geodataMode),
                onSelect = { configuration.geodataMode = booleanValue(it); changed() },
            )
            SelectRow(
                title = stringResource(R.string.tcp_concurrent),
                options = booleans,
                selectedIndex = booleanIndex(configuration.tcpConcurrent),
                onSelect = { configuration.tcpConcurrent = booleanValue(it); changed() },
            )
            SelectRow(
                title = stringResource(R.string.find_process_mode),
                options = listOf(
                    dontModify,
                    stringResource(R.string.off),
                    stringResource(R.string.strict),
                    stringResource(R.string.always),
                ),
                selectedIndex = when (configuration.findProcessMode) {
                    null -> 0
                    ConfigurationOverride.FindProcessMode.Off -> 1
                    ConfigurationOverride.FindProcessMode.Strict -> 2
                    ConfigurationOverride.FindProcessMode.Always -> 3
                },
                onSelect = {
                    configuration.findProcessMode = when (it) {
                        1 -> ConfigurationOverride.FindProcessMode.Off
                        2 -> ConfigurationOverride.FindProcessMode.Strict
                        3 -> ConfigurationOverride.FindProcessMode.Always
                        else -> null
                    }
                    changed()
                },
            )

            SectionHeader(stringResource(R.string.sniffer_setting))

            SelectRow(
                title = stringResource(R.string.strategy),
                options = booleans,
                selectedIndex = booleanIndex(configuration.sniffer.enable),
                onSelect = { configuration.sniffer.enable = booleanValue(it); changed() },
            )

            // Всё ниже настраивает сам sniffer: выключили — настраивать нечего,
            // но строки остаются видимыми, чтобы было понятно, что выключено.
            val sniffing = configuration.sniffer.enable != false

            LinesRow(
                title = stringResource(R.string.sniff_http_ports),
                values = configuration.sniffer.sniff.http.ports,
                onValues = { configuration.sniffer.sniff.http.ports = it; changed() },
                enabled = sniffing,
            )
            SelectRow(
                title = stringResource(R.string.sniff_http_override_destination),
                options = booleans,
                selectedIndex = booleanIndex(configuration.sniffer.sniff.http.overrideDestination),
                onSelect = {
                    configuration.sniffer.sniff.http.overrideDestination = booleanValue(it)
                    changed()
                },
                enabled = sniffing,
            )
            LinesRow(
                title = stringResource(R.string.sniff_tls_ports),
                values = configuration.sniffer.sniff.tls.ports,
                onValues = { configuration.sniffer.sniff.tls.ports = it; changed() },
                enabled = sniffing,
            )
            SelectRow(
                title = stringResource(R.string.sniff_tls_override_destination),
                options = booleans,
                selectedIndex = booleanIndex(configuration.sniffer.sniff.tls.overrideDestination),
                onSelect = {
                    configuration.sniffer.sniff.tls.overrideDestination = booleanValue(it)
                    changed()
                },
                enabled = sniffing,
            )
            LinesRow(
                title = stringResource(R.string.sniff_quic_ports),
                values = configuration.sniffer.sniff.quic.ports,
                onValues = { configuration.sniffer.sniff.quic.ports = it; changed() },
                enabled = sniffing,
            )
            SelectRow(
                title = stringResource(R.string.sniff_quic_override_destination),
                options = booleans,
                selectedIndex = booleanIndex(configuration.sniffer.sniff.quic.overrideDestination),
                onSelect = {
                    configuration.sniffer.sniff.quic.overrideDestination = booleanValue(it)
                    changed()
                },
                enabled = sniffing,
            )
            SelectRow(
                title = stringResource(R.string.force_dns_mapping),
                options = booleans,
                selectedIndex = booleanIndex(configuration.sniffer.forceDnsMapping),
                onSelect = { configuration.sniffer.forceDnsMapping = booleanValue(it); changed() },
                enabled = sniffing,
            )
            SelectRow(
                title = stringResource(R.string.parse_pure_ip),
                options = booleans,
                selectedIndex = booleanIndex(configuration.sniffer.parsePureIp),
                onSelect = { configuration.sniffer.parsePureIp = booleanValue(it); changed() },
                enabled = sniffing,
            )
            SelectRow(
                title = stringResource(R.string.override_destination),
                options = booleans,
                selectedIndex = booleanIndex(configuration.sniffer.overrideDestination),
                onSelect = { configuration.sniffer.overrideDestination = booleanValue(it); changed() },
                enabled = sniffing,
            )
            LinesRow(
                title = stringResource(R.string.force_domain),
                values = configuration.sniffer.forceDomain,
                onValues = { configuration.sniffer.forceDomain = it; changed() },
                enabled = sniffing,
            )
            LinesRow(
                title = stringResource(R.string.skip_domain),
                values = configuration.sniffer.skipDomain,
                onValues = { configuration.sniffer.skipDomain = it; changed() },
                enabled = sniffing,
            )
            LinesRow(
                title = stringResource(R.string.skip_src_address),
                values = configuration.sniffer.skipSrcAddress,
                onValues = { configuration.sniffer.skipSrcAddress = it; changed() },
                enabled = sniffing,
            )
            LinesRow(
                title = stringResource(R.string.skip_dst_address),
                values = configuration.sniffer.skipDstAddress,
                onValues = { configuration.sniffer.skipDstAddress = it; changed() },
                enabled = sniffing,
            )

            SectionHeader(stringResource(R.string.geox_files))

            ActionRow(
                title = stringResource(R.string.import_geoip_file),
                icon = painterResource(R.drawable.ic_outline_folder),
                subtitle = stringResource(R.string.press_to_import),
                onClick = { onAction(MetaFeatureSettingsAction.ImportGeoIp) },
            )
            ActionRow(
                title = stringResource(R.string.import_geosite_file),
                icon = painterResource(R.drawable.ic_outline_folder),
                subtitle = stringResource(R.string.press_to_import),
                onClick = { onAction(MetaFeatureSettingsAction.ImportGeoSite) },
            )
            ActionRow(
                title = stringResource(R.string.import_country_file),
                icon = painterResource(R.drawable.ic_outline_folder),
                subtitle = stringResource(R.string.press_to_import),
                onClick = { onAction(MetaFeatureSettingsAction.ImportCountry) },
            )
            ActionRow(
                title = stringResource(R.string.import_asn_file),
                icon = painterResource(R.drawable.ic_outline_folder),
                subtitle = stringResource(R.string.press_to_import),
                onClick = { onAction(MetaFeatureSettingsAction.ImportAsn) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
