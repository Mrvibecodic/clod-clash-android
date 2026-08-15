package com.github.kr328.clash.service

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.TunPrefs
import com.github.kr328.clash.service.model.DiagnosticsSessionAccess
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.activeTunPrefs
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import com.github.kr328.clash.service.util.parseCIDR
import com.github.kr328.clash.service.util.resolveTunStack
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

class TunService : VpnService(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    private val self: TunService
        get() = this

    private var reason: String? = null

    private var sessionStartedAt: Long = 0

    private val runtime = clashRuntime {
        val store = ServiceStore(self)
        val diagnosticsAccess = DiagnosticsSessionAccess.from(DiagnosticsCredentialStore(self).read())

        val close = install(CloseModule(self))
        val tun = install(TunModule(self))
        val config = install(ConfigurationModule(self, diagnosticsAccess.controller))
        val network = install(NetworkObserveModule(self))

        if (store.dynamicNotification)
            install(DynamicNotificationModule(self))
        else
            install(StaticNotificationModule(self))

        install(AppListCacheModule(self))
        install(TimeZoneModule(self))
        install(SuspendModule(self))
        try {
            tun.open()
            install(DiagnosticsModule(self, diagnosticsAccess.diagnostics))

            while (isActive) {
                val quit = select<Boolean> {
                    close.onEvent {
                        true
                    }
                    config.onEvent {
                        reason = it.message

                        true
                    }
                    network.onEvent { n ->
                        if (Build.VERSION.SDK_INT in 22..28) @TargetApi(22) {
                            setUnderlyingNetworks(n?.let { arrayOf(it) })
                        }

                        false
                    }
                }

                if (quit) break
            }
        } catch (e: Exception) {
            Log.e("Create clash runtime: ${e.message}", e)

            reason = e.message
        } finally {
            withContext(NonCancellable) {
                tun.close()

                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (StatusProvider.serviceRunning)
            return stopSelf()

        StatusProvider.serviceRunning = true

        sessionStartedAt = ServiceStore(this).markSessionStarted()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceStore(this).vpnAlwaysOn = if (isAlwaysOn) 1 else 0
        }

        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)

        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendClashStarted()

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onRevoke() {
        Log.i("TunService revoked")

        stopSelf()
    }

    override fun onDestroy() {
        TunModule.requestStop()

        StatusProvider.serviceRunning = false

        ServiceStore(this).clearSessionStarted(sessionStartedAt)

        sendClashStopped(reason)

        cancelAndJoinBlocking()

        Log.i("TunService destroyed: ${reason ?: "successfully"}")

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }

    private fun TunModule.open() {
        val store = ServiceStore(self)
        val prefs = activeTunPrefs() ?: TunPrefs()
        val includeFromProfile = prefs.includePackages.toSet()
        val excludeFromProfile = prefs.excludePackages.toSet()

        val device = with(Builder()) {
            addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
            if (store.allowIpv6) {
                addAddress(TUN_GATEWAY6, TUN_SUBNET_PREFIX6)
            }

            if (store.bypassPrivateNetwork) {
                resources.getStringArray(R.array.bypass_private_route).map(::parseCIDR).forEach {
                    addRoute(it.ip, it.prefix)
                }
                if (store.allowIpv6) {
                    resources.getStringArray(R.array.bypass_private_route6).map(::parseCIDR).forEach {
                        addRoute(it.ip, it.prefix)
                    }
                }

                addRoute(TUN_DNS, 32)
                if (store.allowIpv6) {
                    addRoute(TUN_DNS6, 128)
                }
            } else {
                addRoute(NET_ANY, 0)
                if (store.allowIpv6) {
                    addRoute(NET_ANY6, 0)
                }
            }

            val installedIncludes = (includeFromProfile - excludeFromProfile).filter {
                runCatching { packageManager.getApplicationInfo(it, 0) }.isSuccess
            }.toSet()

            when (store.accessControlMode) {
                AccessControlMode.AcceptAll -> {
                    if (installedIncludes.isNotEmpty()) {
                        (installedIncludes + packageName).forEach {
                            runCatching { addAllowedApplication(it) }
                        }
                    } else {
                        (excludeFromProfile - packageName).forEach {
                            runCatching { addDisallowedApplication(it) }
                        }
                    }
                }
                AccessControlMode.AcceptSelected -> {
                    (store.accessControlPackages + installedIncludes + packageName).forEach {
                        runCatching { addAllowedApplication(it) }
                    }
                }
                AccessControlMode.DenySelected -> {
                    (store.accessControlPackages + excludeFromProfile - packageName).forEach {
                        runCatching { addDisallowedApplication(it) }
                    }
                }
            }

            setBlocking(false)

            setMtu(TUN_MTU)

            setSession("Clash")

            addDnsServer(TUN_DNS)
            if (store.allowIpv6) {
                addDnsServer(TUN_DNS6)
            }

            setConfigureIntent(
                PendingIntent.getActivity(
                    self,
                    R.id.nf_vpn_status,
                    Intent().setComponent(Components.MAIN_ACTIVITY),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )

            if (Build.VERSION.SDK_INT >= 29) {
                setMetered(false)
            }

            if (Build.VERSION.SDK_INT >= 29 && store.systemProxy) {
                listenHttp()?.let {
                    setHttpProxy(
                        ProxyInfo.buildDirectProxy(
                            it.address.hostAddress,
                            it.port,
                            HTTP_PROXY_BLACK_LIST + if (store.bypassPrivateNetwork) HTTP_PROXY_LOCAL_LIST else emptyList()
                        )
                    )
                }
            }

            if (store.allowBypass) {
                allowBypass()
            }

            TunModule.TunDevice(
                fd = establish()?.detachFd()
                    ?: throw NullPointerException("Establish VPN rejected by system"),
                stack = resolveTunStack(store.tunStackMode, prefs.stack),
                gateway = "$TUN_GATEWAY/$TUN_SUBNET_PREFIX" + if (store.allowIpv6) ",$TUN_GATEWAY6/$TUN_SUBNET_PREFIX6" else "",
                portal = TUN_PORTAL + if (store.allowIpv6) ",$TUN_PORTAL6" else "",
                dns = if (store.dnsHijacking) NET_ANY else (TUN_DNS + if (store.allowIpv6) ",$TUN_DNS6" else ""),
            )
        }

        attach(device)
    }

    companion object {
        private const val TUN_MTU = 9000
        private const val TUN_SUBNET_PREFIX = 30
        private const val TUN_GATEWAY = "172.19.0.1"
        private const val TUN_SUBNET_PREFIX6 = 126
        private const val TUN_GATEWAY6 = "fdfe:dcba:9876::1"
        private const val TUN_PORTAL = "172.19.0.2"
        private const val TUN_PORTAL6 = "fdfe:dcba:9876::2"
        private const val TUN_DNS = TUN_PORTAL
        private const val TUN_DNS6 = TUN_PORTAL6
        private const val NET_ANY = "0.0.0.0"
        private const val NET_ANY6 = "::"

        private val HTTP_PROXY_LOCAL_LIST: List<String> = listOf(
            "localhost",
            "*.local",
            "127.*",
            "10.*",
            "172.16.*",
            "172.17.*",
            "172.18.*",
            "172.19.*",
            "172.2*",
            "172.30.*",
            "172.31.*",
            "192.168.*"
        )
        private val HTTP_PROXY_BLACK_LIST: List<String> = emptyList()
    }
}
