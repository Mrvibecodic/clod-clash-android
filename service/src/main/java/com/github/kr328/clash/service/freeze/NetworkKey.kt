package com.github.kr328.clash.service.freeze

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService
import java.net.Inet4Address
import java.security.MessageDigest

// clod:freeze — по чему итоги проверки 16–20 привязываются к сети.
//
// Мобильная — код оператора (MCC-MNC, без разрешений). Wi-Fi и кабель — шлюз
// маршрута по умолчанию, подсеть, DHCP-сервер (API 30+), DNS и домены сети:
// имя Wi-Fi не берётся, оно отдаётся только с геолокацией. Две квартиры с
// одинаковыми заводскими роутерами считаются одной сетью — это известно.
// В файлы и журнал уходит только хеш.
object NetworkKey {
    // Что из сети нужно ключу: без зависимости от системы в тестах
    data class Seen(
        val transport: String,
        val operator: String = "",
        val gateways: List<String> = emptyList(),
        val subnets: List<String> = emptyList(),
        val dhcp: String = "",
        val dns: List<String> = emptyList(),
        val domains: String = "",
    )

    private const val KEY_HEX_LEN = 16

    fun keyOf(seen: Seen?): String? {
        val parts = partsOf(seen ?: return null) ?: return null

        val digest = MessageDigest.getInstance("SHA-256").digest(parts.toByteArray())

        return digest.joinToString("") { "%02x".format(it) }.take(KEY_HEX_LEN)
    }

    private fun partsOf(seen: Seen): String? = when (seen.transport) {
        "cellular" -> seen.operator.takeIf { it.isNotBlank() }?.let { "cellular;op=$it" }
        else -> {
            if (seen.gateways.isEmpty()) {
                null
            } else {
                listOf(
                    seen.transport,
                    "gw=" + seen.gateways.sorted().joinToString(","),
                    "net=" + seen.subnets.sorted().joinToString(","),
                    "dhcp=" + seen.dhcp,
                    "dns=" + seen.dns.sorted().joinToString(","),
                    "dom=" + seen.domains,
                ).joinToString(";")
            }
        }
    }

    fun seen(context: Context, network: Network?): Seen? {
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return null
        val chosen = network ?: connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(chosen) ?: return null

        // Свой туннель и чужие VPN путём наружу не считаются
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        ) {
            return null
        }

        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "usb"
            else -> "other"
        }

        if (transport == "cellular") {
            val operator = context.getSystemService<TelephonyManager>()?.networkOperator.orEmpty()

            return Seen(transport = transport, operator = operator)
        }

        val link = connectivity.getLinkProperties(chosen) ?: return null

        return seenOf(transport, link)
    }

    private fun seenOf(transport: String, link: LinkProperties): Seen {
        // hasGateway() — API 29; шлюз без адреса или нулевой — не шлюз
        val gateways = preferIpv4(
            link.routes
                .filter { it.isDefaultRoute }
                .mapNotNull { route -> route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress },
        )

        // Подсеть — только IPv4: префикс IPv6 провайдер меняет сам
        val subnets = link.linkAddresses
            .filter { it.address is Inet4Address }
            .map { subnetOf(it.address.address, it.prefixLength) }

        val dhcp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            link.dhcpServerAddress?.hostAddress.orEmpty()
        } else {
            ""
        }

        return Seen(
            transport = transport,
            gateways = gateways,
            subnets = subnets,
            dhcp = dhcp,
            dns = preferIpv4(link.dnsServers.mapNotNull { it.hostAddress }),
            domains = link.domains.orEmpty(),
        )
    }

    // Шлюз и DNS по IPv6 приходят позже IPv4 и меняются с префиксом провайдера —
    // ключ от них плыл бы. Они в счёт только в сети без IPv4
    fun preferIpv4(addresses: List<String>): List<String> =
        addresses.filter { ':' !in it }.ifEmpty { addresses }

    fun subnetOf(address: ByteArray, prefixLength: Int): String {
        val masked = address.copyOf()

        for (index in masked.indices) {
            val bits = (prefixLength - index * 8).coerceIn(0, 8)
            val mask = if (bits == 0) 0 else (0xff shl (8 - bits)) and 0xff

            masked[index] = (masked[index].toInt() and mask).toByte()
        }

        return masked.joinToString(".") { (it.toInt() and 0xff).toString() } + "/" + prefixLength
    }
}
