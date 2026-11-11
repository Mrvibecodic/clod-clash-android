package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.service.ServiceLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select

// Система может снять VPN, не сказав службе (отзыв не дошёл, интерфейс закрыт):
// ключ пропадает, а сессия считает себя живой
class TunWatchModule(service: Service) : Module<Unit>(service) {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val checks = WatchChecks(SystemClock::elapsedRealtime, ServiceLog::mark)

    val summary: String
        get() = checks.summary()

    private fun ours(capabilities: NetworkCapabilities): Boolean =
        ownVpn(Build.VERSION.SDK_INT, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) capabilities.ownerUid else 0, Process.myUid())

    // null — узнать не вышло: тогда не тревожим
    @Suppress("DEPRECATION")
    private fun vpnUp(): Boolean? {
        val up = try {
            connectivity.allNetworks.any {
                val capabilities = connectivity.getNetworkCapabilities(it)

                capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && ours(capabilities)
            }
        } catch (e: Exception) {
            Log.w("Tunnel watch: $e", e)

            checks.note("check failed, ${e.javaClass.simpleName}")

            return null
        }

        checks.note(if (up) "own vpn present" else "own vpn absent")

        return up
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

internal class WatchChecks(private val now: () -> Long, private val mark: (String) -> Unit) {
    private class Check(val outcome: String, val at: Long, val count: Int)

    @Volatile
    private var last: Check? = null

    fun note(outcome: String) {
        val before = last

        if (before?.outcome != outcome) mark("tun watch: $outcome")

        last = Check(outcome, now(), (before?.count ?: 0) + 1)
    }

    fun summary(): String {
        val check = last ?: return "tun watch: no checks"

        return "tun watch: last check ${(now() - check.at) / 1000} s ago, ${check.outcome}, ${check.count} checks"
    }
}

enum class TunLoss { Ignore, Reopen, GiveUp }

// Событие, поставленное до недавнего открытия туннеля, устарело (settled)
fun tunLoss(opened: Boolean, settled: Boolean, budget: () -> Boolean): TunLoss = when {
    !opened || !settled -> TunLoss.Ignore
    !budget() -> TunLoss.GiveUp
    else -> TunLoss.Reopen
}
