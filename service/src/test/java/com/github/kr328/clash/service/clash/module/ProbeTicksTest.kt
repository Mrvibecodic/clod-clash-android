package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTicksTest {
    @Test
    fun `бодрствуем при включённом экране или удержании туннеля`() {
        assertTrue(awake(interactive = true, keepAwake = false))
        assertTrue(awake(interactive = false, keepAwake = true))
        assertFalse(awake(interactive = false, keepAwake = false))
    }

    @Test
    fun `бодрствуем — проба на каждом тике`() {
        assertEquals(1, ticksPerProbe(awake = true))
    }

    @Test
    fun `погашенный экран без удержания — проба через три тика`() {
        assertEquals(IDLE_TICKS_PER_PROBE, ticksPerProbe(awake = false))
        assertEquals(3, ticksPerProbe(awake = false))
    }
}
