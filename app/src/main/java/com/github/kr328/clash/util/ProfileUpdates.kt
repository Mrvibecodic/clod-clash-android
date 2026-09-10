package com.github.kr328.clash.util

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.TimeUnit

object ProfileUpdates {
    val TIMEOUT = TimeUnit.MINUTES.toMillis(15)

    private val GRACE = TimeUnit.SECONDS.toMillis(25)

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

    fun polls(runningIsEmpty: Boolean, activityStarted: Boolean): Boolean =
        !runningIsEmpty && activityStarted

    fun prune() {
        if (deadlines.value.isEmpty()) return

        deadlines.update { it.alive() }
    }

    fun reconcile(actual: Set<UUID>?) {
        if (actual == null) {
            prune()

            return
        }

        val now = SystemClock.elapsedRealtime()

        deadlines.update { current ->
            val kept = current.filter { (uuid, until) ->
                uuid in actual || (until > now && until - now > TIMEOUT - GRACE)
            }

            val added = actual.filter { it !in kept }.associateWith { now + TIMEOUT }

            if (added.isEmpty() && kept.size == current.size) current else kept + added
        }
    }

    private fun Map<UUID, Long>.alive(): Map<UUID, Long> {
        val now = SystemClock.elapsedRealtime()

        return if (all { it.value > now }) this else filterValues { it > now }
    }
}
