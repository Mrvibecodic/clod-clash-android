package com.github.kr328.clash.service.util

object DraftFreshness {
    fun stale(createdAt: Long, touchedAt: Long, directoryModifiedAt: Long, now: Long, maxAge: Long): Boolean {
        val touched = minOf(now, maxOf(createdAt, touchedAt, directoryModifiedAt))

        return now - touched >= maxAge
    }
}
