package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.ProviderValues
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R

// Строки — отдельные остановки фокуса: с пульта длинный список иначе не прокрутить
@Composable
internal fun LineText(text: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 48.dp, end = 8.dp, bottom = 6.dp)
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .focusable(interactionSource = interaction),
    )
}

@Composable
internal fun lineText(line: ProviderValues.Line): String {
    val value = valueText(line.key, line.becomes)

    val becomes = when (line.key) {
        ProviderValues.MODE -> stringResource(R.string.clod_provider_values_from_subscription, value)
        ProviderValues.TUN_STACK -> stringResource(
            R.string.clod_provider_values_auto,
            stringResource(R.string.tun_stack_auto),
            value,
        )
        else -> stringResource(R.string.clod_provider_values_keep, stringResource(R.string.dont_modify), value)
    }

    return stringResource(R.string.clod_provider_values_line, lineLabel(line.key), valueText(line.key, line.now), becomes)
}

@Composable
private fun lineLabel(key: String): String = when (key) {
    ProviderValues.MODE -> stringResource(R.string.clod_mode)
    ProviderValues.TUN_STACK -> stringResource(R.string.tun_stack_mode)
    "dns.enable" -> stringResource(R.string.clod_provider_values_section, stringResource(R.string.dns), stringResource(R.string.strategy))
    "sniffer.enable" -> stringResource(
        R.string.clod_provider_values_section,
        stringResource(R.string.sniffer_setting),
        stringResource(R.string.strategy),
    )
    else -> LINE_LABELS[key]?.let { stringResource(it) } ?: key
}

private val LINE_LABELS = mapOf(
    "log-level" to R.string.log_level,
    "unified-delay" to R.string.unified_delay,
    "port" to R.string.http_port,
    "socks-port" to R.string.socks_port,
    "redir-port" to R.string.redirect_port,
    "tproxy-port" to R.string.tproxy_port,
    "mixed-port" to R.string.mixed_port,
    "allow-lan" to R.string.allow_lan,
    "ipv6" to R.string.ipv6,
    "bind-address" to R.string.bind_address,
    "authentication" to R.string.authentication,
    "dns.prefer-h3" to R.string.prefer_h3,
    "dns.listen" to R.string.listen,
    "dns.ipv6" to R.string.ipv6,
    "dns.use-hosts" to R.string.use_hosts,
    "dns.enhanced-mode" to R.string.enhanced_mode,
    "dns.nameserver" to R.string.name_server,
    "dns.fallback" to R.string.fallback,
    "dns.default-nameserver" to R.string.default_name_server,
    "dns.fake-ip-filter" to R.string.fakeip_filter,
    "dns.fake-ip-filter-mode" to R.string.fakeip_filter_mode,
    "dns.fallback-filter.geoip" to R.string.geoip_fallback,
    "dns.fallback-filter.geoip-code" to R.string.geoip_fallback_code,
    "dns.fallback-filter.ipcidr" to R.string.ipcidr_fallback,
    "dns.fallback-filter.domain" to R.string.domain_fallback,
    "dns.nameserver-policy" to R.string.name_server_policy,
    "hosts" to R.string.hosts,
    "tcp-concurrent" to R.string.tcp_concurrent,
    "find-process-mode" to R.string.find_process_mode,
    "geodata-mode" to R.string.geodata_mode,
    "sniffer.sniff.HTTP.ports" to R.string.sniff_http_ports,
    "sniffer.sniff.HTTP.override-destination" to R.string.sniff_http_override_destination,
    "sniffer.sniff.TLS.ports" to R.string.sniff_tls_ports,
    "sniffer.sniff.TLS.override-destination" to R.string.sniff_tls_override_destination,
    "sniffer.sniff.QUIC.ports" to R.string.sniff_quic_ports,
    "sniffer.sniff.QUIC.override-destination" to R.string.sniff_quic_override_destination,
    "sniffer.force-dns-mapping" to R.string.force_dns_mapping,
    "sniffer.parse-pure-ip" to R.string.parse_pure_ip,
    "sniffer.override-destination" to R.string.override_destination,
    "sniffer.force-domain" to R.string.force_domain,
    "sniffer.skip-domain" to R.string.skip_domain,
    "sniffer.skip-src-address" to R.string.skip_src_address,
    "sniffer.skip-dst-address" to R.string.skip_dst_address,
)

@Composable
internal fun valueText(key: String, json: String): String = when (val value = ProviderValues.value(json)) {
    is ProviderValues.Value.Flag -> stringResource(
        when {
            key == "dns.enable" -> if (value.on) R.string.force_enable else R.string.use_built_in
            value.on -> R.string.enabled
            else -> R.string.disabled
        },
    )
    is ProviderValues.Value.Items -> when {
        value.items.isNotEmpty() -> value.items.joinToString(", ")
        key.startsWith("sniffer.sniff.") && key.endsWith(".ports") -> stringResource(R.string.clod_provider_values_all_ports)
        else -> stringResource(R.string.empty)
    }
    is ProviderValues.Value.Text ->
        if (value.text.isEmpty()) stringResource(R.string.empty) else enumText(key, value.text)
}

@Composable
private fun enumText(key: String, text: String): String {
    if (key == ProviderValues.MODE) {
        val mode = when (text) {
            "rule" -> TunnelState.Mode.Rule
            "global" -> TunnelState.Mode.Global
            "direct" -> TunnelState.Mode.Direct
            else -> null
        }

        return mode?.let { modeLabel(it) } ?: text
    }

    val label = when (key) {
        "log-level" -> when (text) {
            "info" -> R.string.info
            "warning" -> R.string.warning
            "error" -> R.string.error
            "debug" -> R.string.debug
            "silent" -> R.string.silent
            else -> null
        }
        "find-process-mode" -> when (text) {
            "off" -> R.string.off
            "strict" -> R.string.strict
            "always" -> R.string.always
            else -> null
        }
        "dns.enhanced-mode" -> when (text) {
            "normal" -> R.string.disabled
            "fake-ip" -> R.string.fakeip
            "redir-host" -> R.string.mapping
            else -> null
        }
        "dns.fake-ip-filter-mode" -> when (text) {
            "blacklist" -> R.string.blacklist
            "whitelist" -> R.string.whitelist
            "rule" -> R.string.rule
            else -> null
        }
        ProviderValues.TUN_STACK -> when (text) {
            "system" -> R.string.tun_stack_system
            "gvisor" -> R.string.tun_stack_gvisor
            "mixed" -> R.string.tun_stack_mixed
            "mips" -> R.string.tun_stack_mips
            else -> null
        }
        else -> null
    }

    return label?.let { stringResource(it) } ?: text
}
