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
import com.github.kr328.clash.service.ServiceLog
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

class NetworkObserveModule(service: Service) : Module<Network?>(service) {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val networks: Channel<Network?> = Channel(Channel.CONFLATED)
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
        @Volatile var dnsList: List<InetAddress> = emptyList(),
        @Volatile var capabilities: NetworkCapabilities? = null,
    ) {
        fun isAvailable(): Boolean = losingMs < System.currentTimeMillis()
    }

    private val networkInfos = ConcurrentHashMap<Network, NetworkInfo>()

    @Volatile
    private var curDnsList = emptyList<String>()

    private val store = ServiceStore(service)

    private val networkChanges: Channel<String> = Channel(Channel.CONFLATED)

    private val networkReady: Channel<Unit> = Channel(Channel.CONFLATED)

    @Volatile
    private var currentNetwork: Network? = null

    @Volatile
    private var currentValidatedSeen = false

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

    @Volatile
    private var recoverForce = false

    @Volatile
    private var lastProbeAt = Long.MIN_VALUE / 2

    private var idleTicks = 0

    // Reactions since the last validated network, only touched from the run loop
    private val reactionMarks = ArrayList<Long>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("NetworkObserve onAvailable network=$network")
            networkInfos[network] = NetworkInfo()

            onNetworkMaybeChanged(network, "onAvailable")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            networkInfos[network]?.capabilities = networkCapabilities

            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return
            }

            onNetworkMaybeChanged(network, "caps")

            if (network == currentNetwork && !currentValidatedSeen) {
                currentValidatedSeen = true

                networkReady.trySend(Unit)
            }
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            Log.i("NetworkObserve onLosing network=$network")
            networkInfos[network]?.losingMs = System.currentTimeMillis() + maxMsToLive
            notifyDnsChange()
        }

        override fun onLost(network: Network) {
            Log.i("NetworkObserve onLost network=$network")
            networkInfos.remove(network)
            notifyDnsChange()

            val preferred = preferredNetwork()

            if (network == currentNetwork) {
                preferred?.let { onNetworkMaybeChanged(it, "onLost") }
                    ?: markNetworkEvent("onLost", network, "reacted=false (no network left)")
            } else {
                markNetworkEvent("onLost", network, "reacted=false (not current)")
            }

            networks.trySend(preferred)
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

            seedDns()

            true
        } catch (e: Exception) {
            Log.e("NetworkObserve register failed", e)

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
        val capabilities = entry.value.capabilities ?: connectivity.getNetworkCapabilities(entry.key)
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

    private fun describe(network: Network?): String {
        val capabilities = network?.let { networkInfos[it]?.capabilities ?: connectivity.getNetworkCapabilities(it) }

        val transport = when {
            capabilities == null -> "unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }

        return "$network/$transport"
    }

    private fun markNetworkEvent(reason: String, network: Network?, outcome: String) {
        ServiceLog.mark("network changed: reason=$reason network=${describe(network)} $outcome")
    }

    private fun onNetworkMaybeChanged(network: Network, reason: String) {
        // Capability updates repeat for every signal change, so only the ones
        // that pass the filters are worth a journal line
        val journal = reason != "caps"

        if (preferredNetwork()?.equals(network) == false) {
            if (journal) markNetworkEvent(reason, network, "reacted=false (not preferred)")

            return
        }

        if (currentNetwork == network) {
            if (journal) markNetworkEvent(reason, network, "reacted=false (same network)")

            return
        }

        currentNetwork = network
        currentValidatedSeen = false

        if (!networkKnown) {
            networkKnown = true

            markNetworkEvent(reason, network, "reacted=false (initial)")

            return
        }

        Log.i("NetworkObserve network changed to $network")

        networkChanges.trySend(reason)
    }

    private fun handleNetworkChanged(scope: CoroutineScope, reason: String) {
        val now = SystemClock.elapsedRealtime()
        val sinceReset = now - lastResetAt
        if (sinceReset < RESET_THROTTLE_MS) {
            if (!retriggerScheduled) {
                retriggerScheduled = true

                scope.launch {
                    delay(RESET_THROTTLE_MS - sinceReset)

                    retriggerScheduled = false

                    networkChanges.trySend(reason.substringBefore('/') + "/retry")
                }
            }

            markNetworkEvent(reason, currentNetwork, "reacted=false throttled")

            return
        }

        lastResetAt = now

        // A flapping network without validation in between gets a capped series:
        // caches and probes still reset, but connections are no longer torn down.
        reactionMarks.removeAll { now - it >= REACTION_WINDOW_MS }
        reactionMarks.add(now)

        val series = reactionMarks.size
        val reset = store.resetConnectionsOnNetworkChange && series < REACTION_SERIES_LIMIT
        val awake = isInteractive() || store.keepAwake

        markNetworkEvent(reason, currentNetwork, "reacted=true reset=$reset series=$series probe=${if (awake) "now" else "deferred"}")

        Clash.notifyNetworkChanged(reset)

        if (awake) {
            probeNodes()

            scheduleRecover(scope, force = series == 1)
        } else {
            probePending = true
        }
    }

    private fun probeNodes() {
        val now = SystemClock.elapsedRealtime()

        if (now - lastProbeAt < PROBE_MIN_GAP_MS) {
            return
        }

        lastProbeAt = now

        Clash.probeCurrentNodes()
    }

    private fun scheduleRecover(scope: CoroutineScope, force: Boolean) {
        if (force) {
            recoverForce = true
        }

        if (recoverScheduled) {
            return
        }

        recoverScheduled = true

        scope.launch {
            delay(RECOVER_DELAY_MS)

            recoverScheduled = false

            val forced = recoverForce

            recoverForce = false

            if (isInteractive() || store.keepAwake) {
                Clash.recoverDeadNodes(forced)
            } else if (forced) {
                probePending = true
            }
        }
    }

    private fun seedDns() {
        if (curDnsList.isNotEmpty()) {
            return
        }

        try {
            val network = connectivity.activeNetwork ?: return
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return

            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return
            }

            val dnsList = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
                .map { x -> x.asSocketAddressText(53) }

            if (dnsList.isEmpty() || curDnsList.isNotEmpty()) {
                return
            }

            Log.i("NetworkObserve seed dns from ${describe(network)}")

            curDnsList = dnsList

            Clash.notifyDnsChanged(dnsList)
        } catch (e: Exception) {
            Log.w("NetworkObserve seed dns failed", e)
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
        var attempt = 0

        while (!register() && ++attempt < REGISTER_ATTEMPTS) {
            delay(REGISTER_RETRY_MS)
        }

        if (attempt >= REGISTER_ATTEMPTS) {
            ServiceLog.mark("network: callback registration failed after $attempt attempts, running without network watcher")
        }

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
                            handleNetworkChanged(scope, it)
                        }
                        networkReady.onReceive {
                            reactionMarks.clear()

                            Clash.notifyNetworkReady()

                            if (SystemClock.elapsedRealtime() - lastResetAt >= RESET_THROTTLE_MS) {
                                if (isInteractive() || store.keepAwake) {
                                    probeNodes()

                                    scheduleRecover(scope, force = true)
                                } else {
                                    probePending = true
                                }
                            }
                        }
                        screenOn.onReceive {
                            if (probePending) {
                                probePending = false

                                Log.i("NetworkObserve deferred probe after screen on")

                                probeNodes()

                                scheduleRecover(scope, force = false)
                            }
                        }
                        probeTicker.onReceive {
                            val ticksPerProbe = when {
                                isInteractive() -> 1
                                store.keepAwake -> IDLE_TICKS_PER_PROBE_KEEP_AWAKE
                                else -> IDLE_TICKS_PER_PROBE
                            }

                            if (++idleTicks >= ticksPerProbe) {
                                idleTicks = 0

                                probeNodes()
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

        private const val REACTION_WINDOW_MS = 60_000L

        private const val REACTION_SERIES_LIMIT = 3

        private const val RECOVER_DELAY_MS = 7_000L

        private const val PROBE_TICK_MS = 300_000L

        private const val IDLE_TICKS_PER_PROBE = 3

        private const val IDLE_TICKS_PER_PROBE_KEEP_AWAKE = 2

        private const val PROBE_MIN_GAP_MS = 3_000L

        private const val REGISTER_ATTEMPTS = 3

        private const val REGISTER_RETRY_MS = 2_000L
    }
}
