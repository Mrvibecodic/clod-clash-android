package com.github.kr328.clash.design.compose.component

import com.github.kr328.clash.service.model.PanelInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DelayLevelTest {
    @Test
    fun `без заголовка границы 200 и 400`() {
        val bounds = (null as PanelInfo?).pingBounds()

        assertEquals(DelayLevel.Fast, delayLevel(199, bounds))
        assertEquals(DelayLevel.Medium, delayLevel(200, bounds))
        assertEquals(DelayLevel.Medium, delayLevel(399, bounds))
        assertEquals(DelayLevel.Slow, delayLevel(400, bounds))
        assertEquals(PingBounds(), PanelInfo().pingBounds())
    }

    @Test
    fun `границы провайдера меняют уровень`() {
        val bounds = PanelInfo(pingFast = 100, pingMedium = 150).pingBounds()

        assertEquals(DelayLevel.Fast, delayLevel(99, bounds))
        assertEquals(DelayLevel.Medium, delayLevel(100, bounds))
        assertEquals(DelayLevel.Medium, delayLevel(149, bounds))
        assertEquals(DelayLevel.Slow, delayLevel(150, bounds))
        assertEquals(DelayLevel.Slow, delayLevel(399, bounds))
    }

    @Test
    fun `несогласованные границы дают прежние`() {
        assertEquals(PingBounds(), PanelInfo(pingFast = 400, pingMedium = 200).pingBounds())
        assertEquals(PingBounds(), PanelInfo(pingFast = 0, pingMedium = 300).pingBounds())
    }
}
