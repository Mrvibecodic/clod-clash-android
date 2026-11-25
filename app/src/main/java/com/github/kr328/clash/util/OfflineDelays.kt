package com.github.kr328.clash.util

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.freeze.NetworkKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object OfflineDelays {
    sealed interface State {
        data object Idle : State

        data class Running(val profile: UUID, val total: Int, val measured: Int = 0) : State

        data class Done(
            val profile: UUID,
            val delays: Map<String, Int>,
            val error: Exception?,
        ) : State
    }

    private val serializer = MapSerializer(String.serializer(), Int.serializer())

    private val current = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = current

    // Номер запуска: итог и ход замера, погашенного новым, на экран не идут.
    // Сверка номера и запись состояния — под одним замком с новым запуском
    private val runs = AtomicLong()

    private fun publish(run: Long, state: State) = synchronized(runs) {
        if (runs.get() == run) current.value = state
    }

    fun runningFor(profile: UUID): Boolean = (current.value as? State.Running)?.profile == profile

    // Замер другой подписки не ждёт прежнего: ядро гасит прежний само
    fun start(profile: UUID, total: Int) {
        if (runningFor(profile)) return

        launchRun(profile, total, restarts = 0, replaces = null)
    }

    // replaces — перезапуск: только если тот запуск ещё последний и не кончился,
    // иначе человек уже начал новый замер (перезапуск его бы погасил) или видит итог
    private fun launchRun(profile: UUID, total: Int, restarts: Int, replaces: Long?) {
        val run = synchronized(runs) {
            if (replaces != null && (runs.get() != replaces || current.value !is State.Running)) return

            runs.incrementAndGet().also { current.value = State.Running(profile, total) }
        }

        Global.launch {
            var delays = emptyMap<String, Int>()
            var failure: Exception? = null
            var dropped = false

            // Смена сети посреди замера: пробы в разрыве дали бы ложные провалы, а
            // числа — по прежней сети. Замер начинается заново на новой, не больше
            // MAX_RESTARTS раз: мигающая сеть иначе не дала бы ему кончиться.
            // Сессия запускается или идёт — замер без VPN не нужен
            val watch = if (restarts < MAX_RESTARTS) {
                NetworkRestart {
                    if (!Remote.broadcasts.clashActive) launchRun(profile, total, restarts + 1, replaces = run)
                }
            } else {
                null
            }

            try {
                // Не в мобильной сети серверы только для неё не видны и не проверяются
                val raw = coroutineScope {
                    val progress = launch { followProgress(run, profile) }

                    try {
                        // Слежка — когда служба уже есть: перезапуск, пока оба ждут
                        // службу, вошёл бы в ядро раньше прежнего и был бы им погашен
                        val raw = withClash {
                            // Повтор после падения службы: запуск уже заменён — не
                            // входить в ядро, иначе он погасит более новый
                            if (runs.get() != run) return@withClash "{}"

                            watch?.register()

                            testProfileDelays(profile, NetworkKey.cellularNow(Global.application))
                        }

                        // Сеть пропала, и замер домерил в разрыв: итог ложный. Ждём её
                        // возврата и перезапуска; не вернулась — итог как есть
                        if (watch != null && watch.lost) {
                            withTimeoutOrNull(LOST_NETWORK_WAIT_MS) { watch.changed.await() }

                            // За это время включили VPN: ложный итог без VPN не нужен
                            dropped = Remote.broadcasts.clashActive
                        }

                        raw
                    } finally {
                        watch?.unregister()

                        progress.cancel()
                    }
                }

                delays = try {
                    Json.Default.decodeFromString(serializer, raw)
                } catch (e: Exception) {
                    Log.w("Parse offline delays: $e", e)

                    emptyMap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Offline health check: $e", e)

                failure = e
            }

            publish(run, if (dropped) State.Idle else State.Done(profile, delays, failure))
        }
    }

    // Ход замера — только для подписи: сбой опроса замер не прерывает
    private suspend fun followProgress(run: Long, profile: UUID) {
        while (true) {
            delay(PROGRESS_POLL_MS)

            val (measured, total) = try {
                progressOf(withClash { queryProfileDelayProgress() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }

            if (total > 0) publish(run, State.Running(profile, total, measured))
        }
    }

    // Ход из ядра: старшие 32 бита — измерено, младшие — всего
    internal fun progressOf(packed: Long): Pair<Int, Int> =
        (packed ushr 32).toInt() to (packed and 0xffffffffL).toInt()

    private const val PROGRESS_POLL_MS = 1_000L

    private const val MAX_RESTARTS = 3

    private const val LOST_NETWORK_WAIT_MS = 15_000L

    // Сеть наружу сменилась с начала замера: прежняя пропадала (вернись та же —
    // пробы в разрыве уже упали) или сейчас другая. Сети нет — ждать новую
    internal fun <N> networkChanged(startNetwork: N?, startKey: String?, lost: Boolean, active: N?, key: String?): Boolean =
        key != null && (lost || active != startNetwork || key != startKey)

    // Срабатывает один раз. Сеть на старте снимается при регистрации — первые
    // колбэки при ней видят ту же сеть и мимо
    private class NetworkRestart(private val onChanged: () -> Unit) : ConnectivityManager.NetworkCallback() {
        private val connectivity = Global.application.getSystemService<ConnectivityManager>()

        private val registered = AtomicBoolean()

        private val fired = AtomicBoolean()

        val changed = CompletableDeferred<Unit>()

        @Volatile
        private var startNetwork: Network? = null

        @Volatile
        private var startKey: String? = null

        @Volatile
        var lost = false
            private set

        // Повтор вызова после падения службы регистрирует ещё раз — мимо
        fun register() {
            val connectivity = connectivity ?: return

            if (!registered.compareAndSet(false, true)) return

            runCatching {
                startNetwork = connectivity.activeNetwork
                startKey = NetworkKey.keyOf(NetworkKey.seen(Global.application, startNetwork))

                connectivity.registerNetworkCallback(NetworkKey.outwardRequest(), this)
            }.onFailure { Log.w("Offline delays network watch: $it", it) }
        }

        fun unregister() {
            if (registered.get()) runCatching { connectivity?.unregisterNetworkCallback(this) }
        }

        override fun onAvailable(network: Network) = check()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = check()

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = check()

        override fun onLost(network: Network) {
            if (network == startNetwork) lost = true

            check()
        }

        // Колбэк идёт в системном потоке: сбой опроса сети не роняет приложение
        private fun check() {
            try {
                val active = connectivity?.activeNetwork
                val key = NetworkKey.keyOf(NetworkKey.seen(Global.application, active))

                if (networkChanged(startNetwork, startKey, lost, active, key) && fired.compareAndSet(false, true)) {
                    onChanged()

                    changed.complete(Unit)
                }
            } catch (e: Exception) {
                Log.w("Offline delays network watch: $e", e)
            }
        }
    }

    // Убирает только показанный итог: итог следующего замера дождётся показа
    fun consume(done: State.Done) {
        current.compareAndSet(done, State.Idle)
    }
}
