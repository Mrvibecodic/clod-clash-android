package com.github.kr328.clash.design.compose.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairsRowTest {
    @Test
    fun `пары «ключ = значение» и пустые строки проходят`() {
        assertFalse(pairsInvalid(""))
        assertFalse(pairsInvalid("a = 1\n\nb=2\n"))
        assertFalse(pairsInvalid("a ="))
    }

    @Test
    fun `строка без знака равенства блокирует OK`() {
        assertTrue(pairsInvalid("a = 1\nb"))
    }

    @Test
    fun `строка с пустым ключом блокирует OK`() {
        assertTrue(pairsInvalid("=1"))
        assertTrue(pairsInvalid("  = 1"))
    }
}
