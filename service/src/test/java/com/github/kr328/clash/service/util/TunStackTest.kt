package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TunStackTest {
    @Test
    fun explicitModeWins() {
        assertEquals("gvisor", resolveTunStack("gvisor", "system"))
        assertEquals("mixed", resolveTunStack("mixed", ""))
        assertEquals("mips", resolveTunStack("mips", "system"))
        assertEquals("system", resolveTunStack("system", "mips"))
    }

    @Test
    fun autoFallsBackToProfile() {
        assertEquals("system", resolveTunStack("auto", "system"))
        assertEquals("mixed", resolveTunStack("auto", "mixed"))
        assertEquals("mips", resolveTunStack("auto", "mips"))
    }

    @Test
    fun autoWithoutProfileUsesMips() {
        assertEquals("mips", resolveTunStack("auto", ""))
        assertEquals("mips", resolveTunStack("auto", "lwip"))
    }

    @Test
    fun unknownModeUsesProfileThenMips() {
        assertEquals("mixed", resolveTunStack("", "mixed"))
        assertEquals("mips", resolveTunStack("", ""))
    }
}
