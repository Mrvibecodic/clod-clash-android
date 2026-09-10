package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileUpdatesPollingTest {
    @Test
    fun `the background process is polled only while something is updating on a started screen`() {
        assertEquals(false, ProfileUpdates.polls(runningIsEmpty = true, activityStarted = true))
        assertEquals(false, ProfileUpdates.polls(runningIsEmpty = true, activityStarted = false))
        assertEquals(false, ProfileUpdates.polls(runningIsEmpty = false, activityStarted = false))
        assertEquals(true, ProfileUpdates.polls(runningIsEmpty = false, activityStarted = true))
    }
}
