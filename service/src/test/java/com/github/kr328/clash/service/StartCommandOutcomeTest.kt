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
                stopping = false,
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
                stopping = false,
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
                stopping = false,
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
                stopping = false,
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
                stopping = false,
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
                stopping = false,
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
                stopping = false,
                systemStart = false,
                stickyAllowed = true,
                startFailed = false,
                stopped = false,
            ),
        )
    }

    @Test
    fun startInsideStopWindowIsRemembered() {
        assertEquals(
            StartCommandOutcome.Remember,
            startCommandOutcome(
                rejected = false,
                stopping = true,
                systemStart = false,
                stickyAllowed = true,
                startFailed = false,
                stopped = false,
            ),
        )
        assertEquals(
            StartCommandOutcome.Remember,
            startCommandOutcome(
                rejected = false,
                stopping = true,
                systemStart = true,
                stickyAllowed = false,
                startFailed = true,
                stopped = true,
            ),
        )
    }

    @Test
    fun rejectedStillWinsOverStopWindow() {
        assertEquals(
            StartCommandOutcome.Rejected,
            startCommandOutcome(
                rejected = true,
                stopping = true,
                systemStart = false,
                stickyAllowed = true,
                startFailed = false,
                stopped = false,
            ),
        )
    }

    @Test
    fun plainStopDoesNotRestart() {
        assertEquals(
            AfterStopOutcome.Done,
            afterStopOutcome(stopSelfSucceeded = true, restartRequested = false, stickyAllowed = true),
        )
    }

    @Test
    fun heldRequestRestartsSession() {
        assertEquals(
            AfterStopOutcome.StartSession,
            afterStopOutcome(stopSelfSucceeded = true, restartRequested = true, stickyAllowed = true),
        )
        assertEquals(
            AfterStopOutcome.StartSession,
            afterStopOutcome(stopSelfSucceeded = false, restartRequested = false, stickyAllowed = true),
        )
        assertEquals(
            AfterStopOutcome.StartSession,
            afterStopOutcome(stopSelfSucceeded = false, restartRequested = true, stickyAllowed = true),
        )
    }

    @Test
    fun refusedStickyRestartAbandonsHeldRequest() {
        assertEquals(
            AfterStopOutcome.Abandon,
            afterStopOutcome(stopSelfSucceeded = false, restartRequested = true, stickyAllowed = false),
        )
        assertEquals(
            AfterStopOutcome.Abandon,
            afterStopOutcome(stopSelfSucceeded = true, restartRequested = true, stickyAllowed = false),
        )
    }

    @Test
    fun plainStopIgnoresStickyRefusal() {
        assertEquals(
            AfterStopOutcome.Done,
            afterStopOutcome(stopSelfSucceeded = true, restartRequested = false, stickyAllowed = false),
        )
    }
}

class AlwaysOnBusyTest {
    @Test
    fun warnsOnlyWhileTheRunningServiceIsNotReadyYet() {
        assertEquals(true, shouldWarnAlwaysOnBusy(running = true, ready = false, alwaysOn = true))
        assertEquals(false, shouldWarnAlwaysOnBusy(running = true, ready = true, alwaysOn = true))
        assertEquals(false, shouldWarnAlwaysOnBusy(running = true, ready = false, alwaysOn = false))
        assertEquals(false, shouldWarnAlwaysOnBusy(running = true, ready = true, alwaysOn = false))
        assertEquals(false, shouldWarnAlwaysOnBusy(running = false, ready = false, alwaysOn = true))
        assertEquals(false, shouldWarnAlwaysOnBusy(running = false, ready = true, alwaysOn = true))
        assertEquals(false, shouldWarnAlwaysOnBusy(running = false, ready = false, alwaysOn = false))
        assertEquals(false, shouldWarnAlwaysOnBusy(running = false, ready = true, alwaysOn = false))
    }
}
