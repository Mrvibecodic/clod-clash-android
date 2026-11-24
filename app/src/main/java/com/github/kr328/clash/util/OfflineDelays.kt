package com.github.kr328.clash.util

import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.freeze.NetworkKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
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

        val run = synchronized(runs) {
            runs.incrementAndGet().also { current.value = State.Running(profile, total) }
        }

        Global.launch {
            var delays = emptyMap<String, Int>()
            var failure: Exception? = null

            try {
                // Не в мобильной сети серверы только для неё не видны и не проверяются
                val raw = coroutineScope {
                    val progress = launch { followProgress(run, profile) }

                    try {
                        withClash { testProfileDelays(profile, NetworkKey.cellularNow(Global.application)) }
                    } finally {
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

            publish(run, State.Done(profile, delays, failure))
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

    // Убирает только показанный итог: итог следующего замера дождётся показа
    fun consume(done: State.Done) {
        current.compareAndSet(done, State.Idle)
    }
}
