package com.github.kr328.clash.service.clash.module

internal class ReactionSeries(private val windowMs: Long, private val limit: Int) {
    private val marks = ArrayList<Long>()

    fun mark(now: Long): Mark {
        marks.removeAll { now - it >= windowMs }
        marks.add(now)

        return Mark(marks.size, marks.size < limit)
    }

    fun clear() {
        marks.clear()
    }

    data class Mark(val count: Int, val withinLimit: Boolean)
}

internal fun resetsConnections(
    enabled: Boolean,
    unconfirmed: ReactionSeries.Mark,
    flaps: ReactionSeries.Mark,
): Boolean = enabled && unconfirmed.withinLimit && flaps.withinLimit
