package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class DraftFreshnessTest {
    private val maxAge = TimeUnit.HOURS.toMillis(6)
    private val now = TimeUnit.DAYS.toMillis(20000)

    private fun stale(createdAt: Long = 0, touchedAt: Long = 0, directoryModifiedAt: Long = 0) =
        DraftFreshness.stale(createdAt, touchedAt, directoryModifiedAt, now, maxAge)

    @Test
    fun `a draft with no timestamps at all is stale`() {
        assertEquals(true, stale())
    }

    @Test
    fun `creation time alone keeps a fresh draft`() {
        assertEquals(false, stale(createdAt = now - maxAge + 1))
        assertEquals(true, stale(createdAt = now - maxAge))
    }

    @Test
    fun `directory time alone keeps a fresh draft`() {
        assertEquals(false, stale(createdAt = 1, directoryModifiedAt = now - maxAge + 1))
    }

    @Test
    fun `edit time alone keeps a fresh draft`() {
        assertEquals(false, stale(createdAt = 1, touchedAt = now - maxAge + 1))
    }

    @Test
    fun `edit time rescues a draft whose directory time was refused`() {
        assertEquals(
            false,
            stale(createdAt = now - TimeUnit.DAYS.toMillis(3), touchedAt = now - maxAge + 1, directoryModifiedAt = 0),
        )
    }

    @Test
    fun `a timestamp in the future does not make a draft immortal`() {
        assertEquals(false, stale(touchedAt = now + TimeUnit.DAYS.toMillis(1)))
        assertEquals(
            true,
            DraftFreshness.stale(0, now + TimeUnit.DAYS.toMillis(1), 0, now + TimeUnit.DAYS.toMillis(1) + maxAge, maxAge),
        )
    }

    @Test
    fun `the newest of the three timestamps wins`() {
        assertEquals(
            false,
            stale(createdAt = 1, touchedAt = 2, directoryModifiedAt = now - maxAge + 1),
        )
        assertEquals(
            false,
            stale(createdAt = now - maxAge + 1, touchedAt = 2, directoryModifiedAt = 3),
        )
    }
}
