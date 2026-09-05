package com.github.kr328.clash.service.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Private DNS в строгом режиме (с явно указанным хостом) уводит запросы приложений
// в DoT мимо нашего перехвата 53-го порта: fake-ip и правила по доменам перестают работать
suspend fun Context.strictPrivateDnsHost(): String? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@withContext null

    val connectivity = getSystemService<ConnectivityManager>() ?: return@withContext null

    try {
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network)

            // Сеть самого туннеля свойств системного Private DNS не несёт
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true) {
                continue
            }

            val properties = connectivity.getLinkProperties(network) ?: continue

            if (!properties.isPrivateDnsActive) continue

            val host = properties.privateDnsServerName

            if (!host.isNullOrBlank()) return@withContext host
        }

        null
    } catch (e: Exception) {
        Log.w("Read private dns state: $e", e)

        null
    }
}
