package com.github.kr328.clash.service.clash.module

// Сколько событий было в скользящем окне. Отметки из будущего (часы другой
// загрузки) выбрасываются, как и устаревшие
internal class ReactionSeries(private val windowMs: Long, private val limit: Int, recorded: Collection<Long> = emptyList()) {
    private val marks = ArrayList(recorded)

    val recorded: List<Long>
        get() = marks.toList()

    fun mark(now: Long): Mark {
        forget(now)
        marks.add(now)

        return Mark(marks.size, marks.size < limit)
    }

    // Отметить, только если лимит ещё не выбран
    fun take(now: Long): Boolean {
        forget(now)

        if (marks.size >= limit) return false

        marks.add(now)

        return true
    }

    fun clear() {
        marks.clear()
    }

    private fun forget(now: Long) {
        marks.removeAll { it > now || now - it >= windowMs }
    }

    data class Mark(val count: Int, val withinLimit: Boolean)
}

internal fun resetsConnections(
    enabled: Boolean,
    unconfirmed: ReactionSeries.Mark,
    flaps: ReactionSeries.Mark,
): Boolean = enabled && unconfirmed.withinLimit && flaps.withinLimit
