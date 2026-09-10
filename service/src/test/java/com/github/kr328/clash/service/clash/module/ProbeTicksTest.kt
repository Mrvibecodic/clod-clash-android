package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeTicksTest {
    @Test
    fun `экран включён — проба на каждом тике`() {
        assertEquals(1, ticksPerProbe(interactive = true, keepAwake = true))
        assertEquals(1, ticksPerProbe(interactive = true, keepAwake = false))
    }

    @Test
    fun `удержание туннеля — проба на каждом тике и при погашенном экране`() {
        assertEquals(1, ticksPerProbe(interactive = false, keepAwake = true))
    }

    @Test
    fun `погашенный экран без удержания — проба через три тика`() {
        assertEquals(IDLE_TICKS_PER_PROBE, ticksPerProbe(interactive = false, keepAwake = false))
        assertEquals(3, ticksPerProbe(interactive = false, keepAwake = false))
    }
}
