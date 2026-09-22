package com.github.kr328.clash.design.model

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPromptTest {
    private val now = TimeUnit.DAYS.toMillis(1000)
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun askedWhenNeverAnswered() {
        assertTrue(notificationPromptDue(false, false, 0L, 0, now))
    }

    @Test
    fun snoozeHidesUntilSevenDaysPass() {
        assertFalse(notificationPromptDue(false, false, now, 1, now))
        assertFalse(notificationPromptDue(false, false, now - 7 * day + 1, 1, now))
        assertTrue(notificationPromptDue(false, false, now - 7 * day, 1, now))
    }

    @Test
    fun twoSnoozesStopAskingForGood() {
        assertFalse(notificationPromptDue(false, false, now - 365 * day, 2, now))
    }

    @Test
    fun systemRequestIsFinal() {
        assertFalse(notificationPromptDue(false, true, 0L, 0, now))
    }

    @Test
    fun grantedNeverAsks() {
        assertFalse(notificationPromptDue(true, false, 0L, 0, now))
    }
}
