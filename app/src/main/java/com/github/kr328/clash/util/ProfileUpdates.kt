package com.github.kr328.clash.util

import android.os.SystemClock
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.remote.UpdatingProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.TimeUnit

object ProfileUpdates {
    private val FETCH_BUDGET = TimeUnit.SECONDS.toMillis(180)

    private val MIGRATION_PROBE_BUDGET = TimeUnit.SECONDS.toMillis(60)

    val WORST_CASE = FETCH_BUDGET + MIGRATION_PROBE_BUDGET + GeoAssets.READY_TIMEOUT

    val TIMEOUT = WORST_CASE * 2

    private val GRACE = TimeUnit.SECONDS.toMillis(25)

    private val UNAVAILABLE_TIMEOUT = TimeUnit.SECONDS.toMillis(90)

    data class Entry(val deadline: Long, val confirmedAt: Long)

    private val deadlines = MutableStateFlow<Map<UUID, Entry>>(emptyMap())

    val running: StateFlow<Map<UUID, Entry>> = deadlines

    fun start(uuids: Collection<UUID>) = start(uuids, SystemClock.elapsedRealtime())

    internal fun start(uuids: Collection<UUID>, now: Long) {
        if (uuids.isEmpty()) return

        val entry = Entry(deadline = now + TIMEOUT, confirmedAt = now)

        deadlines.update { current -> current.alive(now) + uuids.associateWith { entry } }
    }

    fun finish(uuid: UUID) = finish(uuid, SystemClock.elapsedRealtime())

    internal fun finish(uuid: UUID, now: Long) {
        deadlines.update { (it - uuid).alive(now) }
    }

    fun polls(runningIsEmpty: Boolean, activityStarted: Boolean): Boolean =
        !runningIsEmpty && activityStarted

    fun prune() = prune(SystemClock.elapsedRealtime())

    internal fun prune(now: Long) {
        if (deadlines.value.isEmpty()) return

        deadlines.update { it.alive(now) }
    }

    fun reconcile(actual: UpdatingProfiles) = reconcile(actual, SystemClock.elapsedRealtime())

    internal fun reconcile(actual: UpdatingProfiles, now: Long) {
        when (actual) {
            UpdatingProfiles.Unavailable -> deadlines.update { current ->
                val kept = current.filterValues {
                    it.deadline > now && now - it.confirmedAt < UNAVAILABLE_TIMEOUT
                }

                if (kept.size == current.size) current else kept
            }
            is UpdatingProfiles.Known -> deadlines.update { current ->
                val kept = current
                    .filter { (uuid, entry) ->
                        uuid in actual.uuids ||
                            (entry.deadline > now && entry.deadline - now > TIMEOUT - GRACE)
                    }
                    .mapValues { (uuid, entry) ->
                        if (uuid in actual.uuids) entry.copy(confirmedAt = now) else entry
                    }

                val added = actual.uuids
                    .filter { it !in kept }
                    .associateWith { Entry(deadline = now + TIMEOUT, confirmedAt = now) }

                if (added.isEmpty() && kept == current) current else kept + added
            }
        }
    }

    private fun Map<UUID, Entry>.alive(now: Long): Map<UUID, Entry> =
        if (all { it.value.deadline > now }) this else filterValues { it.deadline > now }
}
