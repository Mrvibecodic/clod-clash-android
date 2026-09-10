package com.github.kr328.clash.service.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun <T> pickSystemResolvers(
    candidates: List<T>,
    notVpn: (T) -> Boolean,
    resolvers: (T) -> List<String>,
): List<String> =
    candidates.asSequence()
        .filter(notVpn)
        .map(resolvers)
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

suspend fun Context.seedSystemDns() {
    withContext(Dispatchers.IO) {
        try {
            if (StatusProvider.serviceRunning) {
                return@withContext
            }

            val connectivity = getSystemService<ConnectivityManager>()

            if (connectivity == null) {
                Log.w("Seed system dns: no connectivity manager")

                return@withContext
            }

            @Suppress("DEPRECATION")
            val all = connectivity.allNetworks

            val candidates = buildList {
                connectivity.activeNetwork?.let { add(it) }

                addAll(all)
            }.distinct()

            val dnsList = pickSystemResolvers(
                candidates = candidates,
                notVpn = { network ->
                    connectivity.getNetworkCapabilities(network)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
                },
                resolvers = { network ->
                    connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
                        .map { it.asSocketAddressText(53) }
                },
            )

            if (dnsList.isEmpty()) {
                Log.w("Seed system dns: no resolvers on ${candidates.size} non-VPN network(s)")

                return@withContext
            }

            Log.i("Seed system dns before fetch: $dnsList")

            Clash.notifyDnsChanged(dnsList)
        } catch (e: Exception) {
            Log.w("Seed system dns: $e", e)
        }
    }
}
