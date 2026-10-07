package com.github.kr328.clash.service.clash.module

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.bridge.ClashException
import com.github.kr328.clash.core.util.parseInetSocketAddress
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.ServiceLog
import kotlinx.coroutines.awaitCancellation
import java.net.InetSocketAddress
import java.security.SecureRandom

class TunModule(private val vpn: VpnService) : Module<Unit>(vpn) {
    data class TunDevice(
        val fd: Int,
        var stack: String,
        val gateway: String,
        val portal: String,
        val dns: String,
    )

    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private var http: InetSocketAddress? = null

    private fun queryUid(
        protocol: Int,
        source: InetSocketAddress,
        target: InetSocketAddress,
    ): Int {
        if (Build.VERSION.SDK_INT < 29)
            return -1

        return runCatching { connectivity.getConnectionOwnerUid(protocol, source, target) }
            .getOrElse { -1 }
    }

    // Туннель останавливает finally рантайма TunService — один раз, до конца сессии
    override suspend fun run() {
        awaitCancellation()
    }

    // Вход живёт всю сессию: пересборка туннеля отдаёт системе тот же адрес, иначе
    // приложения, запомнившие прежний прокси, получают отказ до своего перезапуска.
    fun listenHttp(): InetSocketAddress? {
        http?.let { return it }

        val r = { 1 + random.nextInt(199) }
        val listenAt = "127.${r()}.${r()}.${r()}:0"

        return Clash.startHttp(listenAt)?.let(::parseInetSocketAddress).also { http = it }
    }

    fun attachSocketCallbacks() {
        Clash.attachSocketCallbacks(markSocket = vpn::protect, querySocketUid = this::queryUid)
    }

    fun attach(device: TunDevice) {
        ServiceLog.mark("tun: attach")

        try {
            Clash.startTun(
                fd = device.fd,
                stack = device.stack,
                gateway = device.gateway,
                portal = device.portal,
                dns = device.dns,
            )
        } catch (e: Exception) {
            Log.e("Start tun failed", e)

            throw ClashException(service.getString(R.string.clod_tun_start_failed))
        }
    }

    companion object {
        private val random = SecureRandom()

        fun requestStop() {
            ServiceLog.mark("tun: stop requested")

            Clash.stopHttp()
            Clash.stopTun()

            ServiceLog.mark("tun: stopped")
        }
    }
}
