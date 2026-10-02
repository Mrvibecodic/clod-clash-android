package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHealthCheckTest {
    private val stale = 300_000L

    @Test
    fun `перезагрузка прокси уже начала замер`() {
        assertFalse(
            shouldAutoHealthCheck(
                clashRunning = true,
                startedByReload = true,
                groupsKnown = true,
                readOnly = false,
                sinceLastCheckMs = 1_000_000_000L,
                staleMs = stale,
            ),
        )
    }

    @Test
    fun `без известных групп замер не начинается`() {
        assertFalse(
            shouldAutoHealthCheck(
                clashRunning = true,
                startedByReload = false,
                groupsKnown = false,
                readOnly = false,
                sinceLastCheckMs = 1_000_000_000L,
                staleMs = stale,
            ),
        )
    }

    @Test
    fun `список только для чтения замер не начинает`() {
        assertFalse(
            shouldAutoHealthCheck(
                clashRunning = true,
                startedByReload = false,
                groupsKnown = true,
                readOnly = true,
                sinceLastCheckMs = 1_000_000_000L,
                staleMs = stale,
            ),
        )
    }

    @Test
    fun `ровно на границе троттла замер не начинается`() {
        assertFalse(
            shouldAutoHealthCheck(
                clashRunning = true,
                startedByReload = false,
                groupsKnown = true,
                readOnly = false,
                sinceLastCheckMs = stale,
                staleMs = stale,
            ),
        )
    }

    @Test
    fun `на миллисекунду позже границы замер начинается`() {
        assertTrue(
            shouldAutoHealthCheck(
                clashRunning = true,
                startedByReload = false,
                groupsKnown = true,
                readOnly = false,
                sinceLastCheckMs = stale + 1,
                staleMs = stale,
            ),
        )
    }

    @Test
    fun `сразу после предыдущего замера новый не начинается`() {
        assertFalse(
            shouldAutoHealthCheck(
                clashRunning = true,
                startedByReload = false,
                groupsKnown = true,
                readOnly = false,
                sinceLastCheckMs = 0,
                staleMs = stale,
            ),
        )
    }

    @Test
    fun `при выключенном туннеле автоматический замер не начинается`() {
        assertFalse(
            shouldAutoHealthCheck(
                clashRunning = false,
                startedByReload = false,
                groupsKnown = true,
                readOnly = false,
                sinceLastCheckMs = 1_000_000_000L,
                staleMs = stale,
            ),
        )
    }

    @Test
    fun `панель из подписки меряется только по кнопке, даже при запущенном туннеле`() {
        assertEquals(HealthCheckRoute.Skip, healthCheckRoute(offlinePanel = true, manual = false))
        assertEquals(HealthCheckRoute.Offline, healthCheckRoute(offlinePanel = true, manual = true))
    }

    @Test
    fun `живые группы меряются и сами, и по кнопке`() {
        assertEquals(HealthCheckRoute.Live, healthCheckRoute(offlinePanel = false, manual = false))
        assertEquals(HealthCheckRoute.Live, healthCheckRoute(offlinePanel = false, manual = true))
    }
}
