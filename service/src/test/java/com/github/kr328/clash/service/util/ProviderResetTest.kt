package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ProviderValues
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderResetTest {
    @Test
    fun `settings of the core reload the profile, which also decides mode and stack`() {
        assertEquals(
            setOf(ProviderReset.Reload),
            providerResets(listOf("log-level", ProviderValues.MODE, ProviderValues.MODE_CHOICE, ProviderValues.TUN_STACK)),
        )
    }

    @Test
    fun `mode alone switches live`() {
        assertEquals(setOf(ProviderReset.Mode), providerResets(listOf(ProviderValues.MODE_CHOICE)))
        assertEquals(setOf(ProviderReset.Mode), providerResets(listOf(ProviderValues.MODE)))
        assertEquals(setOf(ProviderReset.Mode), providerResets(listOf(ProviderValues.MODE_CHOICE, ProviderValues.MODE)))
    }

    @Test
    fun `stack alone only reopens the tunnel`() {
        assertEquals(setOf(ProviderReset.Stack), providerResets(listOf(ProviderValues.TUN_STACK)))
    }

    @Test
    fun `mode and stack together`() {
        assertEquals(
            setOf(ProviderReset.Mode, ProviderReset.Stack),
            providerResets(listOf(ProviderValues.MODE, ProviderValues.TUN_STACK)),
        )
    }

    @Test
    fun `nothing chosen does nothing`() {
        assertEquals(emptySet<ProviderReset>(), providerResets(emptyList()))
    }
}
