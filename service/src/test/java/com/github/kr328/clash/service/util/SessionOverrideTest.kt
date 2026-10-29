package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.TunnelState
import org.junit.Assert.assertEquals
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
}
