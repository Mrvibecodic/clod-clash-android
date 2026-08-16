package com.github.kr328.clash.service.util

object SessionClock {
    fun seconds(
        startedAt: Long,
        startedElapsed: Long,
        nowWall: Long,
        nowElapsed: Long,
    ): Long {
        if (startedElapsed in 1..nowElapsed) {
            return (nowElapsed - startedElapsed) / 1000
        }

        if (startedAt > 0) {
            return ((nowWall - startedAt) / 1000).coerceAtLeast(0)
        }

        return 0
    }
}
