package com.github.kr328.clash.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverflowGapTest {
    @Test
    fun `lost lines are counted onto the next accepted line`() {
        val gap = OverflowGap()
        val sent = mutableListOf<Int>()

        assertFalse(gap.offer { sent += it; true })
        assertTrue(gap.offer { false })
        assertFalse(gap.offer { false })
        assertFalse(gap.offer { false })
        assertFalse(gap.offer { sent += it; true })
        assertFalse(gap.offer { sent += it; true })

        assertEquals(listOf(0, 3, 0), sent)

        // Новая серия потерь снова отмечается
        assertTrue(gap.offer { false })
    }

    // Каждая строка — одна попытка отправки: отметка о потерях не занимает
    // отдельного места в очереди
    @Test
    fun `every line is offered exactly once`() {
        val gap = OverflowGap()
        var sends = 0

        repeat(100) { i -> gap.offer { sends++; i % 3 == 0 } }

        assertEquals(100, sends)
    }
}
