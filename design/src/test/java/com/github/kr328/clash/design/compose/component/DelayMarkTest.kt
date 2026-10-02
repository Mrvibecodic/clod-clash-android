package com.github.kr328.clash.design.compose.component

import com.github.kr328.clash.service.model.PanelInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DelayMarkTest {
    @Test
    fun `без заголовка границы 200 и 400`() {
        val bounds = (null as PanelInfo?).pingBounds()

        assertEquals(DelayMark.Fast, delayMark(199, bounds))
        assertEquals(DelayMark.Medium, delayMark(200, bounds))
        assertEquals(DelayMark.Medium, delayMark(399, bounds))
        assertEquals(DelayMark.Slow, delayMark(400, bounds))
        assertEquals(PingBounds(), PanelInfo().pingBounds())
    }

    @Test
    fun `границы провайдера меняют уровень`() {
        val bounds = PanelInfo(pingFast = 100, pingMedium = 150).pingBounds()

        assertEquals(DelayMark.Fast, delayMark(99, bounds))
        assertEquals(DelayMark.Medium, delayMark(100, bounds))
        assertEquals(DelayMark.Medium, delayMark(149, bounds))
        assertEquals(DelayMark.Slow, delayMark(150, bounds))
        assertEquals(DelayMark.Slow, delayMark(399, bounds))
    }

    @Test
    fun `несогласованные границы дают прежние`() {
        assertEquals(PingBounds(), PanelInfo(pingFast = 400, pingMedium = 200).pingBounds())
        assertEquals(PingBounds(), PanelInfo(pingFast = 0, pingMedium = 300).pingBounds())
    }

    @Test
    fun `не промеренный узел и не ответивший различаются при любых границах`() {
        for (bounds in listOf(PingBounds(), PingBounds(100, 150))) {
            assertEquals(DelayMark.Untested, delayMark(0, bounds))
            assertEquals(DelayMark.Untested, delayMark(-1, bounds))
            assertEquals(DelayMark.Dead, delayMark(0xffff, bounds))
            assertEquals(DelayMark.Slow, delayMark(0xfffe, bounds))
        }
    }
}
