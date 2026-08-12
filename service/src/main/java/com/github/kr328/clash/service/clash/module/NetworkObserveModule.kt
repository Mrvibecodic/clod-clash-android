package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.net.*
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.asSocketAddressText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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

    /**
     * Смена сети: сюда падает сигнал из колбэков, а разбирается он в цикле
     * модуля. Канал схлопывающийся — при переезде Wi-Fi → LTE система сыплет
     * колбэками пачкой, а сделать надо один раз.
     */
    private val networkChanges: Channel<Unit> = Channel(Channel.CONFLATED)

    /**
     * Сеть, которую мы считаем текущей. Сравнивается по объекту: система даёт
     * новый `Network` на каждое подключение, поэтому даже возврат на тот же
     * Wi-Fi после провала — это смена сети, и обойтись без сброса нельзя.
     */
    @Volatile
    private var currentNetwork: Network? = null

    /**
     * Первое определение сети сменой не считается: при старте службы рвать
     * ещё нечего, а проба только зря разбудит радиомодуль.
     */
    @Volatile
    private var networkKnown = false

    /** Когда сбрасывали в последний раз, по часам без учёта сна. */
    @Volatile
    private var lastResetAt = 0L

    /** Экран был выключен в момент смены — пробу должны догнать при включении. */
    @Volatile
    private var probePending = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("NetworkObserve onAvailable network=$network")
            networkInfos[network] = NetworkInfo()

            onNetworkMaybeChanged(network)
        }

        /**
         * Сеть подтвердила, что через неё есть интернет.
         *
         * Появление интерфейса ещё ничего не значит: Wi-Fi может отвечать
         * на подключение и не пускать дальше портала, а LTE — подниматься
         * секундами. `NET_CAPABILITY_VALIDATED` — единственный сигнал системы,
         * означающий «проверено, интернет тут есть», и действовать надо по нему.
         */
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

            // САМЫЙ ЧАСТЫЙ СЛУЧАЙ: ушёл Wi-Fi, а LTE уже был поднят фоном.
            // Нового `onAvailable` для него не будет — система про него давно
            // сообщила, — и без этой ветки смена сети прошла бы незамеченной.
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

    private fun networkToInt(entry: Map.Entry<Network, NetworkInfo>): Int {
        val capabilities = connectivity.getNetworkCapabilities(entry.key)
        // calculate priority based on transport type, available state
        // lower value means higher priority
        // wifi > ethernet > usb tethering > bluetooth tethering > cellular > satellite > other
        return when {
            capabilities == null -> 100
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 90
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> 5
            // TRANSPORT_LOWPAN / TRANSPORT_THREAD / TRANSPORT_WIFI_AWARE are not for general internet access, which will not set as default route.
            else -> 20
        } + (if (entry.value.isAvailable()) 0 else 10)
    }

    /**
     * Похоже, сеть сменилась — сообщить об этом циклу модуля.
     *
     * Проверка «стала ли эта сеть предпочтительной» отсекает фон: телефон
     * держит и Wi-Fi, и LTE одновременно, и появление второй сети при живой
     * первой ничего для нас не меняет.
     */
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

    /**
     * Сеть сменилась: сбросить состояние ядра и, если экран включён,
     * проверить текущий узел.
     *
     * Сброс дешёвый и без сети — делается всегда. Проба стоит запроса,
     * поэтому при выключенном экране откладывается до включения: разбудить
     * радиомодуль ради цифры, которую некому показать, незачем.
     */
    private fun handleNetworkChanged() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastResetAt < RESET_THROTTLE_MS) {
            Log.d("NetworkObserve reset throttled")

            return
        }

        lastResetAt = now

        Clash.notifyNetworkChanged(store.resetConnectionsOnNetworkChange)

        if (isInteractive()) {
            Clash.probeCurrentNodes()
        } else {
            probePending = true
        }
    }

    private fun isInteractive(): Boolean =
        service.getSystemService<PowerManager>()?.isInteractive ?: true

    /**
     * Сеть, которой телефон пользуется прямо сейчас: с наименьшим весом
     * по [networkToInt]. Их всегда несколько — Wi-Fi и LTE живут одновременно.
     */
    private fun preferredNetwork(): Network? =
        networkInfos.asSequence().minByOrNull { networkToInt(it) }?.key

    private fun notifyDnsChange() {
        val dnsList = (networkInfos.asSequence().minByOrNull { networkToInt(it) }?.value?.dnsList
            ?: emptyList()).map { x -> x.asSocketAddressText(53) }
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
            while (true) {
                select<Unit> {
                    networks.onReceive {
                        enqueueEvent(it)
                    }
                    networkChanges.onReceive {
                        handleNetworkChanged()
                    }
                    screenOn.onReceive {
                        if (probePending) {
                            probePending = false

                            Log.i("NetworkObserve deferred probe after screen on")

                            Clash.probeCurrentNodes()
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
        /**
         * Переезд из сети в сеть система показывает пачкой колбэков за доли
         * секунды. Пять секунд по переднему фронту: первый сигнал срабатывает
         * сразу, остальные из той же пачки пропускаются.
         */
        private const val RESET_THROTTLE_MS = 5_000L
    }
}
