package com.github.kr328.clash.service.freeze

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.util.sha256Hex
import java.net.Inet4Address

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

        return sha256Hex(parts).take(KEY_HEX_LEN)
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

    // Вид сети для отчёта прослойке: wired | wifi | mobile | other
    fun kindOf(seen: Seen?): String = when (seen?.transport) {
        "wifi" -> "wifi"
        "ethernet", "usb" -> "wired"
        "cellular" -> "mobile"
        else -> "other"
    }

    // Мобильная ли сеть (SIM); null — сеть не названа
    fun cellular(seen: Seen?): Boolean? = seen?.let { it.transport == "cellular" }

    // Сеть наружу и её место в очереди: первой идёт та, что выбрала бы служба
    data class Outward(val transport: String, val rank: Int)

    // Очередь сетей наружу, одна для службы и экрана: Wi-Fi, кабель, USB,
    // Bluetooth, SIM, спутник; не проверенная системой — после проверенных
    // (на ней бывает только окно входа), но остаётся сетью — при белых
    // списках у оператора сеть SIM проверку не проходит, а работает
    fun rankOf(transport: String, validated: Boolean): Int = when (transport) {
        "wifi" -> 0
        "ethernet" -> 1
        "usb" -> 2
        "bluetooth" -> 3
        "cellular" -> 4
        "satellite" -> 5
        else -> 20
    } + if (validated) 0 else 10

    fun rank(capabilities: NetworkCapabilities): Int {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return 90

        val transport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)
        ) {
            "satellite"
        } else {
            transportOf(capabilities)
        }

        return rankOf(transport, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    // Сеть наружу: не VPN (свой туннель и чужие), с интернетом, без ограничений
    // и не фоновая (с Android 9 SIM при основной Wi-Fi держится фоновой). Один
    // набор для экрана и службы: иначе экран считал бы сеть мобильной по
    // фоновой SIM, а служба — нет
    private val OUTWARD_CAPABILITIES: List<Int> = listOfNotNull(
        NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
        NetworkCapabilities.NET_CAPABILITY_INTERNET,
        NetworkCapabilities.NET_CAPABILITY_FOREGROUND.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P },
        NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
    )

    fun outwardRequest(): NetworkRequest =
        NetworkRequest.Builder().apply { OUTWARD_CAPABILITIES.forEach { addCapability(it) } }.build()

    fun outwardOf(capabilities: NetworkCapabilities): Outward? {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            OUTWARD_CAPABILITIES.any { !capabilities.hasCapability(it) }
        ) {
            return null
        }

        return Outward(transportOf(capabilities), rank(capabilities))
    }

    // Мобильная, когда первая из сетей наружу — сеть SIM; сетей нет — не мобильная
    fun cellularPreferred(networks: Collection<Outward>): Boolean =
        networks.minByOrNull { it.rank }?.transport == "cellular"

    // Мобильная ли сеть устройства сейчас — по тому же правилу, что у службы
    @Suppress("DEPRECATION")
    fun cellularNow(context: Context): Boolean {
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return false

        return cellularPreferred(connectivity.allNetworks.mapNotNull { connectivity.getNetworkCapabilities(it)?.let(::outwardOf) })
    }

    // Транспорт сети: входит в ключ и в вид сети для отчёта — значения не менять
    fun transportOf(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "usb"
        else -> "other"
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

        val transport = transportOf(capabilities)

        if (transport == "cellular") {
            return Seen(transport = transport, operator = dataOperator(context))
        }

        val link = connectivity.getLinkProperties(chosen) ?: return null

        return seenOf(transport, link)
    }

    // Оператор SIM для интернета: без уточнения система отдаёт SIM для звонков
    private fun dataOperator(context: Context): String {
        val telephony = context.getSystemService<TelephonyManager>() ?: return ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val data = SubscriptionManager.getDefaultDataSubscriptionId()

            if (data != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return telephony.createForSubscriptionId(data).networkOperator.orEmpty()
            }
        }

        return telephony.networkOperator.orEmpty()
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
