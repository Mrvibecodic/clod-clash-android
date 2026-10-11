package com.github.kr328.clash.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopNoticeTitleTest {
    @Test
    fun readySessionStoppedByFailureIsAnnounced() {
        assertEquals(R.string.clod_stopped_title, stopNoticeTitle(wasReady = true, systemStarted = false, unattended = false))
    }

    @Test
    fun startBySystemOrUnattendedIsAnnouncedAsStartFailure() {
        assertEquals(R.string.clod_start_failed, stopNoticeTitle(wasReady = false, systemStarted = true, unattended = false))
        assertEquals(R.string.clod_start_failed, stopNoticeTitle(wasReady = false, systemStarted = false, unattended = true))
    }

    @Test
    fun startFromScreenThatFailedIsLeftToTheScreen() {
        assertNull(stopNoticeTitle(wasReady = false, systemStarted = false, unattended = false))
    }
}
