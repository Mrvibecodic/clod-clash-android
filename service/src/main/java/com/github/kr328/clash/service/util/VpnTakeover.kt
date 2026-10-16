package com.github.kr328.clash.service.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Отзыв разрешения на VPN система присылает одинаково, выключил ли человек VPN
// сам (в настройках, в системном окне VPN) или разрешение забрало другое
// приложение. Различить можно только по тому, что следом поднимается чужая
// VPN-сеть: другое приложение получает разрешение и сразу создаёт свой туннель.
// Сам выключил — остановка тихая, без уведомления.
object VpnTakeover {
    private const val WATCH_MS = 10_000L
    private const val POLL_MS = 500L

    fun watch(context: Context, onTakeover: () -> Unit) {
        val before = vpnNetworks(context)

        Global.launch {
            var waited = 0L

            while (waited < WATCH_MS) {
                delay(POLL_MS)
                waited += POLL_MS

                // Человек снова включил VPN у нас: служба отмечает себя
                // запущенной раньше, чем поднимает туннель, так что новая
                // VPN-сеть здесь никогда не своя
                if (StatusProvider.serviceRunning) return@launch

                if (tookOver(before, vpnNetworks(context))) {
                    Log.i("VPN permission taken by another app")

                    onTakeover()

                    return@launch
                }
            }

            Log.i("VPN turned off outside the app, no other VPN came up")
        }
    }

    // Сеть VPN, которой не было в момент отзыва (своя к нему уже была), — чужая
    fun tookOver(before: Set<Network>, now: Set<Network>): Boolean = (now - before).isNotEmpty()

    @Suppress("DEPRECATION")
    private fun vpnNetworks(context: Context): Set<Network> {
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return emptySet()

        return connectivity.allNetworks.filterTo(HashSet()) {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }
}
