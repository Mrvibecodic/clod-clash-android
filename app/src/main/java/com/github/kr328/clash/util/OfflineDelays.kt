package com.github.kr328.clash.util

import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

object OfflineDelays {
    sealed interface State {
        data object Idle : State

        data class Running(val profile: UUID, val total: Int, val manual: Boolean) : State

        data class Done(
            val profile: UUID,
            val manual: Boolean,
            val delays: Map<String, Int>,
            val error: Exception?,
        ) : State
    }

    private val serializer = MapSerializer(String.serializer(), Int.serializer())

    private val current = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = current

    val running: Boolean
        get() = current.value is State.Running

    fun start(profile: UUID, total: Int, manual: Boolean) {
        if (current.value is State.Running) return

        current.value = State.Running(profile, total, manual)

        Global.launch {
            var delays = emptyMap<String, Int>()
            var failure: Exception? = null

            try {
                val raw = withClash { testProfileDelays(profile) }

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

            current.value = State.Done(profile, manual, delays, failure)
        }
    }

    fun consume() {
        if (current.value is State.Done) {
            current.value = State.Idle
        }
    }
}
