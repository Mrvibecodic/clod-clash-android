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
}
