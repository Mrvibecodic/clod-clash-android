package com.github.kr328.clash.design.compose.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusContainerColorTest {
    @Test
    fun zeroAlphaKeepsBackdrop() {
        assertEquals(Color.White, statusContainerColor(Color.White, Color.Black, 0f))
    }

    @Test
    fun fullAlphaKeepsAccent() {
        assertEquals(Color.Black, statusContainerColor(Color.White, Color.Black, 1f))
    }

    @Test
    fun partialAlphaStaysBetweenBackdropAndAccent() {
        val mixed = statusContainerColor(Color.White, Color.Black, 0.14f)

        assertTrue(mixed.red < 1f && mixed.red > 0f)
        assertTrue(mixed.green < 1f && mixed.green > 0f)
        assertTrue(mixed.blue < 1f && mixed.blue > 0f)

        assertTrue(mixed.red > 0.5f)
    }

    @Test
    fun biggerAlphaMovesFurtherFromBackdrop() {
        val near = statusContainerColor(Color.White, Color.Black, 0.14f)
        val far = statusContainerColor(Color.White, Color.Black, 0.5f)

        assertTrue(far.red < near.red)
    }

    @Test
    fun differentBackdropsGiveDifferentResults() {
        val accent = Color(0xFF2E7D32)

        val low = statusContainerColor(Color(0xFFF5F5F5), accent, 0.14f)
        val container = statusContainerColor(Color(0xFFD7E3F4), accent, 0.14f)

        assertNotEquals(low, container)
    }
}
