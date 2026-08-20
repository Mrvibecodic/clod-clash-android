package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.net.*
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.asSocketAddressText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class NetworkObserveModule(service: Service) : Module<Network>(service) {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val networks: Channel<Network> = Channel(Channel.UNLIMITED)
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()

    private data class NetworkInfo(
        @Volatile var losingMs: Long = 0,
        @Volatile var dnsList: List<InetAddress> = emptyList()
    ) {
        fun isAvailable(): Boolean = losingMs < System.currentTimeMillis()
    }

    private val networkInfos = ConcurrentHashMap<Network, NetworkInfo>()

    @Volatile
    private var curDnsList = emptyList<String>()

    private val store = ServiceStore(service)

    private val networkChanges: Channel<Unit> = Channel(Channel.CONFLATED)

    @Volatile
    private var currentNetwork: Network? = null

    @Volatile
    private var networkKnown = false

    @Volatile
    private var lastResetAt = 0L

    @Volatile
    private var probePending = false

    @Volatile
    private var retriggerScheduled = false

    @Volatile
    private var recoverScheduled = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("NetworkObserve onAvailable network=$network")
            networkInfos[network] = NetworkInfo()

            onNetworkMaybeChanged(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return
            }

            onNetworkMaybeChanged(network)
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            Log.i("NetworkObserve onLosing network=$network")
            networkInfos[network]?.losingMs = System.currentTimeMillis() + maxMsToLive
            notifyDnsChange()

            networks.trySend(network)
        }

        override fun onLost(network: Network) {
            Log.i("NetworkObserve onLost network=$network")
            networkInfos.remove(network)
            notifyDnsChange()

            if (network == currentNetwork) {
                preferredNetwork()?.let(::onNetworkMaybeChanged)
            }

            networks.trySend(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.i("NetworkObserve onLinkPropertiesChanged network=$network $linkProperties")
            networkInfos[network]?.dnsList = linkProperties.dnsServers
            notifyDnsChange()

            networks.trySend(network)
        }

        override fun onUnavailable() {
            Log.i("NetworkObserve onUnavailable")
        }
    }

    private fun register(): Boolean {
        Log.i("NetworkObserve start register")
        return try {
            connectivity.registerNetworkCallback(request, callback)

            true
        } catch (e: Exception) {
            Log.w("NetworkObserve register failed", e)

            false
        }
    }

    private fun unregister(): Boolean {
        Log.i("NetworkObserve start unregister")
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("NetworkObserve unregister failed", e)
        }

        return false
    }

    private fun unvalidatedPenalty(capabilities: NetworkCapabilities): Int {
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 0 else 10
    }

    private fun networkToInt(entry: Map.Entry<Network, NetworkInfo>): Int {
        val capabilities = connectivity.getNetworkCapabilities(entry.key)
        return when {
            capabilities == null -> 100
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 90
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> 5
            else -> 20
        } + (if (entry.value.isAvailable()) 0 else 10) +
            (if (capabilities == null) 0 else unvalidatedPenalty(capabilities))
    }

    private fun onNetworkMaybeChanged(network: Network) {
        if (preferredNetwork()?.equals(network) == false) {
            return
        }

        if (currentNetwork == network) {
            return
        }

        currentNetwork = network

        if (!networkKnown) {
            networkKnown = true

            return
        }

        Log.i("NetworkObserve network changed to $network")

        networkChanges.trySend(Unit)
    }

    private fun handleNetworkChanged(scope: CoroutineScope) {
        val now = SystemClock.elapsedRealtime()
        val sinceReset = now - lastResetAt
        if (sinceReset < RESET_THROTTLE_MS) {
            if (!retriggerScheduled) {
                retriggerScheduled = true

                scope.launch {
                    delay(RESET_THROTTLE_MS - sinceReset)

                    retriggerScheduled = false

                    networkChanges.trySend(Unit)
                }
            }

            Log.d("NetworkObserve reset throttled, retry after window")

            return
        }

        lastResetAt = now

        Clash.notifyNetworkChanged(store.resetConnectionsOnNetworkChange)

        if (isInteractive() || store.keepAwake) {
            Clash.probeCurrentNodes()

            scheduleRecover(scope)
        } else {
            probePending = true
        }
    }

    private fun scheduleRecover(scope: CoroutineScope) {
        if (recoverScheduled) {
            return
        }

        recoverScheduled = true

        scope.launch {
            delay(RECOVER_DELAY_MS)

            recoverScheduled = false

            if (isInteractive() || store.keepAwake) {
                Clash.recoverDeadNodes()
            }
        }
    }

    private fun isInteractive(): Boolean =
        service.getSystemService<PowerManager>()?.isInteractive ?: true

    private fun preferredNetwork(): Network? =
        networkInfos.asSequence().minByOrNull { networkToInt(it) }?.key

    private fun preferredDnsList(): List<InetAddress> {
        return networkInfos.asSequence()
            .sortedBy { networkToInt(it) }
            .map { it.value.dnsList }
            .firstOrNull { it.isNotEmpty() }
            ?: emptyList()
    }

    private fun notifyDnsChange() {
        val dnsList = preferredDnsList().map { x -> x.asSocketAddressText(53) }
        val prevDnsList = curDnsList
        if (dnsList.isNotEmpty() && prevDnsList != dnsList) {
            Log.i("notifyDnsChange $prevDnsList -> $dnsList")
            curDnsList = dnsList
            Clash.notifyDnsChanged(dnsList)
        }
    }

    override suspend fun run() {
        register()

        val screenOn = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
        }

        try {
            coroutineScope {
                val scope = this

                val probeTicker = scope.ticker(PROBE_TICK_MS)

                while (true) {
                    select<Unit> {
                        networks.onReceive {
                            enqueueEvent(it)
                        }
                        networkChanges.onReceive {
                            handleNetworkChanged(scope)
                        }
                        screenOn.onReceive {
                            if (probePending) {
                                probePending = false

                                Log.i("NetworkObserve deferred probe after screen on")

                                Clash.probeCurrentNodes()
                            }

                            Clash.recoverDeadNodes()
                        }
                        probeTicker.onReceive {
                            if (isInteractive() || store.keepAwake) {
                                Clash.probeCurrentNodes()
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                unregister()

                Log.i("NetworkObserve dns = []")
                Clash.notifyDnsChanged(emptyList())
            }
        }
    }

    companion object {
        private const val RESET_THROTTLE_MS = 5_000L

        private const val RECOVER_DELAY_MS = 8_000L

        private const val PROBE_TICK_MS = 300_000L
    }
}
