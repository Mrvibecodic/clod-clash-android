package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOverrideTest {
    @Test
    fun choiceOfTheProfileGoesIntoTheSession() {
        for (mode in listOf(TunnelState.Mode.Rule, TunnelState.Mode.Global, TunnelState.Mode.Direct)) {
            assertEquals(ConfigurationOverride(mode = mode), sessionOverrideFor(mode))
        }
    }

    @Test
    fun noChoiceClearsTheSession() {
        assertEquals(ConfigurationOverride(), sessionOverrideFor(null))
    }

    @Test
    fun repeatingTheStoredChoiceChangesNothing() {
        for (mode in listOf(null, TunnelState.Mode.Rule, TunnelState.Mode.Global, TunnelState.Mode.Direct)) {
            assertFalse(modeChoiceChanged(mode, mode))
        }
    }

    @Test
    fun anotherChoiceIsAChange() {
        assertTrue(modeChoiceChanged(null, TunnelState.Mode.Global))
        assertTrue(modeChoiceChanged(TunnelState.Mode.Global, null))
        assertTrue(modeChoiceChanged(TunnelState.Mode.Rule, TunnelState.Mode.Direct))
    }
}
