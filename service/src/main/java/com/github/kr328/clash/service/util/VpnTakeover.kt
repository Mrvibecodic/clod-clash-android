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
    private const val WATCH_MS = 30_000L
    private const val POLL_MS = 500L

    // ownGone — своей VPN-сети уже нет (её пропажу заметил сторож): любая
    // VPN-сеть, поднятая сейчас или в окне, — чужая
    fun watch(context: Context, ownGone: Boolean = false, onTakeover: () -> Unit) {
        val before = if (ownGone) emptySet() else vpnNetworks(context)

        Global.launch {
            var waited = 0L
            var stopped = !StatusProvider.serviceRunning

            while (waited < WATCH_MS) {
                delay(POLL_MS)
                waited += POLL_MS

                val running = StatusProvider.serviceRunning
                stopped = stopped || !running

                when (poll(stopped, running, before, vpnNetworks(context))) {
                    Poll.Restarted -> return@launch
                    Poll.TookOver -> {
                        Log.i("VPN permission taken by another app")

                        onTakeover()

                        return@launch
                    }
                    Poll.Wait -> Unit
                }
            }

            Log.i("VPN turned off outside the app, no other VPN came up")
        }
    }

    enum class Poll { Wait, Restarted, TookOver }

    // Один опрос. Служба остановилась и запущена снова — человек включил VPN у
    // нас: она отмечает себя запущенной раньше, чем поднимает туннель, так что
    // новая VPN-сеть тогда не чужая. Иначе VPN-сеть, которой не было в начале, — чужая
    fun <T> poll(stopped: Boolean, running: Boolean, before: Set<T>, now: Set<T>): Poll = when {
        stopped && running -> Poll.Restarted
        (now - before).isNotEmpty() -> Poll.TookOver
        else -> Poll.Wait
    }

    @Suppress("DEPRECATION")
    private fun vpnNetworks(context: Context): Set<Network> {
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return emptySet()

        return connectivity.allNetworks.filterTo(HashSet()) {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }
}
