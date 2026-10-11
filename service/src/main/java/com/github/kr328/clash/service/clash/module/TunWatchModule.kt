package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.ticker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select

// Система может снять VPN, не сказав службе (отзыв не дошёл, интерфейс закрыт):
// ключ пропадает, а сессия считает себя живой
class TunWatchModule(service: Service) : Module<Unit>(service) {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!

    private fun ours(capabilities: NetworkCapabilities): Boolean =
        ownVpn(Build.VERSION.SDK_INT, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) capabilities.ownerUid else 0, Process.myUid())

    // null — узнать не вышло: тогда не тревожим
    @Suppress("DEPRECATION")
    private fun vpnUp(): Boolean? = try {
        connectivity.allNetworks.any {
            val capabilities = connectivity.getNetworkCapabilities(it)

            capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && ours(capabilities)
        }
    } catch (e: Exception) {
        Log.w("Tunnel watch: $e", e)

        null
    }

    override suspend fun run() = coroutineScope {
        val screenOn = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
        }

        val tick = ticker(WATCH_MS)

        while (true) {
            select<Unit> {
                screenOn.onReceive { }
                tick.onReceive { }
            }

            if (vpnUp() != false) continue

            delay(RECHECK_MS)

            if (vpnUp() == false) enqueueEvent(Unit)
        }
    }

    private companion object {
        const val WATCH_MS = 60_000L
        const val RECHECK_MS = 5_000L
    }
}

// Своя ли VPN-сеть. С Android 11 своему VPN-приложению ownerUid виден, а у
// чужих VPN (рабочий профиль, Private Space, другой пользователь) он скрыт — их
// не считаем. Раньше ownerUid приложениям не отдаётся: считаем своей любую
fun ownVpn(sdk: Int, ownerUid: Int, myUid: Int): Boolean = sdk < 30 || ownerUid == myUid

enum class TunLoss { Ignore, Reopen, GiveUp }

// Событие, поставленное до недавнего открытия туннеля, устарело (settled)
fun tunLoss(opened: Boolean, settled: Boolean, budget: () -> Boolean): TunLoss = when {
    !opened || !settled -> TunLoss.Ignore
    !budget() -> TunLoss.GiveUp
    else -> TunLoss.Reopen
}
