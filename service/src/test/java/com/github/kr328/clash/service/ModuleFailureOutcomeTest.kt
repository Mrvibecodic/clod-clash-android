package com.github.kr328.clash.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModuleFailureOutcomeTest {
    @Test
    fun failureOfStoppingOrPreviousSessionIsIgnoredWithoutSpendingTheBudget() {
        var spent = false

        assertEquals(ModuleFailureOutcome.Ignore, moduleFailureOutcome(live = false, sessionAlive = true) { spent = true; true })
        assertEquals(ModuleFailureOutcome.Ignore, moduleFailureOutcome(live = true, sessionAlive = false) { spent = true; true })
        assertFalse(spent)
    }

    @Test
    fun failureOfLiveSessionRestartsWhileTheBudgetLastsThenFails() {
        assertEquals(ModuleFailureOutcome.Restart, moduleFailureOutcome(live = true, sessionAlive = true) { true })
        assertEquals(ModuleFailureOutcome.Fail, moduleFailureOutcome(live = true, sessionAlive = true) { false })
    }
}
