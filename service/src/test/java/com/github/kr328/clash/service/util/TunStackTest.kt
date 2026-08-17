package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TunStackTest {
    @Test
    fun explicitModeWins() {
        assertEquals("gvisor", resolveTunStack("gvisor", "system"))
        assertEquals("mixed", resolveTunStack("mixed", ""))
    }

    @Test
    fun autoFallsBackToProfile() {
        assertEquals("gvisor", resolveTunStack("auto", "gvisor"))
        assertEquals("system", resolveTunStack("auto", "system"))
    }

    @Test
    fun autoWithoutProfileUsesSystem() {
        assertEquals("system", resolveTunStack("auto", ""))
        assertEquals("system", resolveTunStack("auto", "lwip"))
    }

    @Test
    fun unknownModeUsesProfileThenSystem() {
        assertEquals("mixed", resolveTunStack("", "mixed"))
        assertEquals("system", resolveTunStack("", ""))
    }
}
