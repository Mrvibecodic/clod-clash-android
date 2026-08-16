package com.github.kr328.clash.util

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.TimeUnit

object ProfileUpdates {
    private val TIMEOUT = TimeUnit.SECONDS.toMillis(90)

    private val deadlines = MutableStateFlow<Map<UUID, Long>>(emptyMap())

    val running: StateFlow<Map<UUID, Long>> = deadlines

    fun start(uuids: Collection<UUID>) {
        if (uuids.isEmpty()) return

        val until = SystemClock.elapsedRealtime() + TIMEOUT

        deadlines.update { current -> current.alive() + uuids.associateWith { until } }
    }

    fun finish(uuid: UUID) {
        deadlines.update { (it - uuid).alive() }
    }

    fun prune() {
        if (deadlines.value.isEmpty()) return

        deadlines.update { it.alive() }
    }

    private fun Map<UUID, Long>.alive(): Map<UUID, Long> {
        val now = SystemClock.elapsedRealtime()

        return if (all { it.value > now }) this else filterValues { it > now }
    }
}
