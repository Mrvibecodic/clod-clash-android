package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpdateScheduleTest {
    private val minutes15 = TimeUnit.MINUTES.toMillis(15)
    private val hours6 = TimeUnit.HOURS.toMillis(6)

    @Test
    fun `a profile without auto update gets no retry at all`() {
        assertNull(UpdateSchedule.retryDelay(0, 1))
        assertNull(UpdateSchedule.retryDelay(TimeUnit.MINUTES.toMillis(14), 1))
    }

    @Test
    fun `the first retry comes in a quarter of an hour`() {
        assertEquals(minutes15, UpdateSchedule.retryDelay(hours6, 1))
    }

    @Test
    fun `retries back off by doubling`() {
        assertEquals(TimeUnit.MINUTES.toMillis(30), UpdateSchedule.retryDelay(hours6, 2))
        assertEquals(TimeUnit.MINUTES.toMillis(60), UpdateSchedule.retryDelay(hours6, 3))
        assertEquals(TimeUnit.MINUTES.toMillis(120), UpdateSchedule.retryDelay(hours6, 4))
        assertEquals(TimeUnit.MINUTES.toMillis(240), UpdateSchedule.retryDelay(hours6, 5))
    }

    @Test
    fun `the back off is capped by the profile interval`() {
        assertEquals(hours6, UpdateSchedule.retryDelay(hours6, 6))
        assertEquals(hours6, UpdateSchedule.retryDelay(hours6, 40))
    }

    @Test
    fun `the shortest interval keeps the quarter of an hour cadence`() {
        assertEquals(minutes15, UpdateSchedule.retryDelay(minutes15, 1))
        assertEquals(minutes15, UpdateSchedule.retryDelay(minutes15, 9))
    }

    @Test
    fun `an out of range attempt still yields a delay inside the interval`() {
        assertEquals(hours6, UpdateSchedule.retryDelay(hours6, Int.MAX_VALUE))
        assertEquals(minutes15, UpdateSchedule.retryDelay(hours6, 0))
    }
}
