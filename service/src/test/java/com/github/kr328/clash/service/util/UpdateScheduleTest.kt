package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

class UpdateSchedulePeriodTest {
    private val minutes15 = TimeUnit.MINUTES.toMillis(15)
    private val hours6 = TimeUnit.HOURS.toMillis(6)

    @Test
    fun `a failure is retried inside the period while the back off is shorter than the period`() {
        assertTrue(UpdateSchedule.retryWithinPeriod(hours6, 1))
        assertTrue(UpdateSchedule.retryWithinPeriod(hours6, 5))
    }

    @Test
    fun `once the back off reaches the period the next regular run is the retry`() {
        assertFalse(UpdateSchedule.retryWithinPeriod(hours6, 6))
        assertFalse(UpdateSchedule.retryWithinPeriod(minutes15, 1))
    }

    @Test
    fun `a profile without auto update is never retried`() {
        assertFalse(UpdateSchedule.retryWithinPeriod(0, 1))
    }

    @Test
    fun `the first run comes when the current period of the file ends`() {
        val now = 1_000_000_000L

        assertEquals(hours6, UpdateSchedule.firstDelay(hours6, now, now))
        assertEquals(hours6 - minutes15, UpdateSchedule.firstDelay(hours6, now - minutes15, now))
    }

    @Test
    fun `an overdue file is fetched right away`() {
        val now = 1_000_000_000L

        assertEquals(0, UpdateSchedule.firstDelay(hours6, now - 2 * hours6, now))
        assertEquals(0, UpdateSchedule.firstDelay(hours6, 0, now))
    }

    @Test
    fun `a file from the future waits no longer than one period`() {
        val now = 1_000_000_000L

        assertEquals(hours6, UpdateSchedule.firstDelay(hours6, now + hours6, now))
    }
}

class UpdateScheduleIntervalOwnerTest {
    private val hours6 = TimeUnit.HOURS.toMillis(6)
    private val hours12 = TimeUnit.HOURS.toMillis(12)

    @Test
    fun `the panel value is used only when the interval is not manual`() {
        assertEquals(hours12, UpdateSchedule.effectiveInterval(false, hours12, hours6))
        assertEquals(0, UpdateSchedule.effectiveInterval(false, 0, hours6))
        assertEquals(hours6, UpdateSchedule.effectiveInterval(true, hours12, hours6))
    }

    @Test
    fun `without a panel value the own interval stays`() {
        assertEquals(hours6, UpdateSchedule.effectiveInterval(false, null, hours6))
        assertEquals(0, UpdateSchedule.effectiveInterval(false, null, 0))
    }
}

class UpdateScheduleExpiryTest {
    private val now = 1_700_000_000_000L
    private val grace = UpdateSchedule.EXPIRY_GRACE
    private val slack = UpdateSchedule.EXPIRY_SLACK

    @Test
    fun `the expiry fetch is due once after the panel deadline`() {
        val expire = now + TimeUnit.MINUTES.toMillis(10)

        assertEquals(expire + grace, UpdateSchedule.expiryFetchAt(expire, 0, now - 1))
        // Загрузка заметно раньше дедлайна — ещё нужна.
        assertEquals(expire + grace, UpdateSchedule.expiryFetchAt(expire, 0, expire + grace - slack - 1))
        // Загрузка за считанные секунды до дедлайна (дрожание поправки часов) — уже в срок.
        assertNull(UpdateSchedule.expiryFetchAt(expire, 0, expire + grace - slack))
        // Загрузка уже была после дедлайна — второй раз не нужна.
        assertNull(UpdateSchedule.expiryFetchAt(expire, 0, expire + grace))
        // Ни разу не загружали.
        assertEquals(expire + grace, UpdateSchedule.expiryFetchAt(expire, 0, 0))
    }

    @Test
    fun `the deadline follows the panel clock`() {
        val expire = now + TimeUnit.MINUTES.toMillis(10)
        val minute = TimeUnit.MINUTES.toMillis(1)

        // Панель спешит на минуту — по часам устройства срок наступает раньше.
        assertEquals(expire - minute + grace, UpdateSchedule.expiryFetchAt(expire, minute, now))
        assertEquals(expire + minute + grace, UpdateSchedule.expiryFetchAt(expire, -minute, now))
    }

    @Test
    fun `a subscription without a deadline is never fetched for it`() {
        assertNull(UpdateSchedule.expiryFetchAt(0, 0, 0))
        assertNull(UpdateSchedule.expiryFetchAt(-1, 0, 0))
    }

    @Test
    fun `a fetch made by a stale clock is re-judged by the fresh measurement`() {
        // Поправка состарилась (0), устройство спешит на 5 минут: задача взвелась
        // на expire + 90 с по часам устройства, загрузка ушла и удалась…
        val minutes5 = TimeUnit.MINUTES.toMillis(5)
        val expire = now
        val fetched = now + grace

        assertEquals(expire + grace, UpdateSchedule.expiryFetchAt(expire, 0, now - TimeUnit.HOURS.toMillis(1)))
        // …а её ответ принёс свежий замер −5 мин: по нему та же загрузка была до
        // срока панели, цель стоит и переставлена на expire + 5 мин + 90 с.
        assertEquals(expire + minutes5 + grace, UpdateSchedule.expiryFetchAt(expire, -minutes5, fetched))
    }

    @Test
    fun `seconds and milliseconds name the same deadline`() {
        val expireSeconds = now / 1000 + 600

        assertEquals(
            UpdateSchedule.expiryFetchAt(expireSeconds * 1000, 0, now),
            UpdateSchedule.expiryFetchAt(expireSeconds, 0, now),
        )
    }
}
