package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeGateTest {
    private val gap = 3_000L

    private val never = Long.MIN_VALUE / 2

    @Test
    fun `проба сразу после предыдущей отбрасывается`() {
        assertFalse(shouldProbe(10_000L, 9_999L, gap, force = false))
    }

    @Test
    fun `проба ровно на границе окна проходит`() {
        assertTrue(shouldProbe(10_000L, 7_000L, gap, force = false))
    }

    @Test
    fun `проба на миллисекунду раньше границы не проходит`() {
        assertFalse(shouldProbe(10_000L, 7_001L, gap, force = false))
    }

    @Test
    fun `форсированная проба проходит внутри окна`() {
        assertTrue(shouldProbe(10_000L, 9_999L, gap, force = true))
    }

    @Test
    fun `первая проба за сессию проходит`() {
        assertTrue(shouldProbe(10_000L, never, gap, force = false))
    }

    @Test
    fun `начало монотонных часов не переполняется`() {
        assertTrue(shouldProbe(never, never, gap, force = true))
        assertFalse(shouldProbe(never, never, gap, force = false))
    }
}
