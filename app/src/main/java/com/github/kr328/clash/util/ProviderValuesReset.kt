package com.github.kr328.clash.util

import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

object ProviderValuesReset {
    sealed interface State {
        data object Idle : State

        data class Running(val profile: UUID) : State

        data class Done(val profile: UUID, val error: Exception?) : State
    }

    private val current = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = current

    fun start(profile: UUID, keys: List<String>) {
        if (current.value is State.Running) return

        current.value = State.Running(profile)

        Global.launch {
            var failure: Exception? = null

            try {
                withClash { resetProviderValues(profile, keys) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Reset provider values: $e", e)

                failure = e
            }

            current.value = State.Done(profile, failure)
        }
    }

    suspend fun awaitIdle() {
        current.first { it !is State.Running }
    }

    fun consume() {
        if (current.value is State.Done) {
            current.value = State.Idle
        }
    }
}
