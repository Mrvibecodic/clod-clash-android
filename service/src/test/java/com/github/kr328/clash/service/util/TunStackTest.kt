package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ProviderValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    @Test
    fun profileStackIsPassedThrough() {
        assertEquals("lwip", resolveTunStack("auto", "lwip"))
        assertEquals("system", resolveTunStack("auto", " System "))
    }

    @Test
    fun unknownModeUsesProfileThenMips() {
        assertEquals("mixed", resolveTunStack("", "mixed"))
        assertEquals("mips", resolveTunStack("", ""))
    }

    @Test
    fun providerStackLineOnlyWhenOwnStackBeatsProfile() {
        assertEquals(
            ProviderValues.Line(ProviderValues.TUN_STACK, "\"system\"", "\"mixed\"", listOf(ProviderValues.TUN_STACK)),
            providerTunStack("system", "mixed"),
        )

        assertNull(providerTunStack(TUN_STACK_AUTO, "mixed"))
        assertNull(providerTunStack("system", ""))
        assertNull(providerTunStack("mixed", "mixed"))
    }
}
