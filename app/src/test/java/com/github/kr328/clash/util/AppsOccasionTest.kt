package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppsOccasionTest {
    @Test
    fun `повод достаётся одному расчёту`() {
        val occasion = AppsOccasion()

        occasion.owe(true)

        assertEquals(true, occasion.take())
        assertNull(occasion.take())
    }

    @Test
    fun `без повода расчёта никто не ждёт`() {
        assertNull(AppsOccasion().take())
    }

    @Test
    fun `расчёт без повода тоже ждут — лист остаётся каким был`() {
        val occasion = AppsOccasion()

        occasion.owe(false)

        assertEquals(false, occasion.take())
    }

    @Test
    fun `повод не снимается более поздним «каким был»`() {
        val occasion = AppsOccasion()

        occasion.owe(true)
        occasion.owe(false)

        assertEquals(true, occasion.take())
    }

    @Test
    fun `расчёт, не дошедший до экрана, возвращает повод`() {
        val occasion = AppsOccasion()

        occasion.owe(true)
        occasion.giveBack(occasion.take())

        assertEquals(true, occasion.take())
    }

    @Test
    fun `повод, пришедший пока расчёт шёл, остаётся следующему`() {
        val occasion = AppsOccasion()

        occasion.owe(true)
        occasion.take()
        occasion.owe(true)

        assertEquals(true, occasion.take())
    }

    @Test
    fun `возврат «каким был» не снимает повод, пришедший за время расчёта`() {
        val occasion = AppsOccasion()

        occasion.owe(false)

        val taken = occasion.take()

        occasion.owe(true)
        occasion.giveBack(taken)

        assertEquals(true, occasion.take())
    }

    @Test
    fun `расчёт, которого не ждали, возвращать нечего`() {
        val occasion = AppsOccasion()

        occasion.giveBack(occasion.take())

        assertNull(occasion.take())
    }
}
