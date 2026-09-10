package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasuringBudgetTest {
    @Test
    fun `бюджет не меньше общего потолка раунда`() {
        assertEquals(45, measuringBudgetSeconds(0))
        assertEquals(45, measuringBudgetSeconds(1))
        assertEquals(45, measuringBudgetSeconds(50))
    }

    @Test
    fun `бюджет растёт по десяткам узлов`() {
        assertEquals(60, measuringBudgetSeconds(100))
        assertEquals(110, measuringBudgetSeconds(200))
        assertEquals(160, measuringBudgetSeconds(300))
        assertEquals(260, measuringBudgetSeconds(500))
        assertEquals(510, measuringBudgetSeconds(1000))
    }

    @Test
    fun `оценка в минутах округляется вверх`() {
        assertEquals(1, measuringMinutes(0))
        assertEquals(1, measuringMinutes(1))
        assertEquals(1, measuringMinutes(100))
        assertEquals(2, measuringMinutes(200))
        assertEquals(3, measuringMinutes(300))
        assertEquals(5, measuringMinutes(500))
        assertEquals(9, measuringMinutes(1000))
    }
}
