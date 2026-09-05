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

suspend fun Context.seedSystemDns() {
    withContext(Dispatchers.IO) {
        try {
            if (StatusProvider.serviceRunning) {
                return@withContext
            }

            val connectivity = getSystemService<ConnectivityManager>() ?: return@withContext
            val network = connectivity.activeNetwork ?: return@withContext
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@withContext

            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return@withContext
            }

            val dnsList = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
                .map { it.asSocketAddressText(53) }

            if (dnsList.isEmpty()) {
                return@withContext
            }

            Log.i("Seed system dns before fetch: $dnsList")

            Clash.notifyDnsChanged(dnsList)
        } catch (e: Exception) {
            Log.w("Seed system dns: $e", e)
        }
    }
}
