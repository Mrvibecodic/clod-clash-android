package com.github.kr328.clash.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StartCommandOutcomeTest {
    @Test
    fun rejectedWinsOverEverything() {
        assertEquals(
            StartCommandOutcome.Rejected,
            startCommandOutcome(
                rejected = true,
                systemStart = true,
                stickyAllowed = false,
                startFailed = true,
                stopped = true,
            ),
        )
    }

    @Test
    fun refusedStickyRestartStops() {
        assertEquals(
            StartCommandOutcome.StopSticky,
            startCommandOutcome(
                rejected = false,
                systemStart = true,
                stickyAllowed = false,
                startFailed = true,
                stopped = true,
            ),
        )
    }

    @Test
    fun allowedStickyRestartFallsThrough() {
        assertEquals(
            StartCommandOutcome.StartSession,
            startCommandOutcome(
                rejected = false,
                systemStart = true,
                stickyAllowed = true,
                startFailed = false,
                stopped = true,
            ),
        )
    }

    @Test
    fun refusedStickyRestartIgnoredOnUserStart() {
        assertEquals(
            StartCommandOutcome.Ignore,
            startCommandOutcome(
                rejected = false,
                systemStart = false,
                stickyAllowed = false,
                startFailed = false,
                stopped = false,
            ),
        )
    }

    @Test
    fun startFailedStops() {
        assertEquals(
            StartCommandOutcome.StopStartFailed,
            startCommandOutcome(
                rejected = false,
                systemStart = false,
                stickyAllowed = true,
                startFailed = true,
                stopped = true,
            ),
        )
    }

    @Test
    fun stoppedServiceStartsNewSession() {
        assertEquals(
            StartCommandOutcome.StartSession,
            startCommandOutcome(
                rejected = false,
                systemStart = false,
                stickyAllowed = true,
                startFailed = false,
                stopped = true,
            ),
        )
    }

    @Test
    fun liveServiceIgnoresRepeatedStart() {
        assertEquals(
            StartCommandOutcome.Ignore,
            startCommandOutcome(
                rejected = false,
                systemStart = false,
                stickyAllowed = true,
                startFailed = false,
                stopped = false,
            ),
        )
    }
}
