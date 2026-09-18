package com.github.kr328.clash.service.util

import java.util.concurrent.TimeUnit

object UpdateSchedule {
    val MIN_INTERVAL: Long = TimeUnit.MINUTES.toMillis(15)

    fun retryDelay(interval: Long, attempt: Int): Long? {
        if (interval < MIN_INTERVAL)
            return null

        return (MIN_INTERVAL shl (attempt - 1).coerceIn(0, 16))
            .coerceIn(MIN_INTERVAL, interval.coerceAtLeast(MIN_INTERVAL))
    }

    fun retryWithinPeriod(interval: Long, attempt: Int): Boolean {
        val delay = retryDelay(interval, attempt) ?: return false

        return delay < interval
    }

    fun effectiveInterval(manual: Boolean, panel: Long?, own: Long): Long {
        if (manual || panel == null)
            return own

        return panel
    }

    fun firstDelay(interval: Long, updatedAt: Long, now: Long): Long {
        if (updatedAt <= 0)
            return 0

        return (interval - (now - updatedAt)).coerceIn(0, interval)
    }
}
