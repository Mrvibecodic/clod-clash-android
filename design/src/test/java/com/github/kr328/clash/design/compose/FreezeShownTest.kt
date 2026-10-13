package com.github.kr328.clash.design.compose

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.compose.screen.ProxyGroupState
import com.github.kr328.clash.design.compose.screen.ServersState
import org.junit.Assert.assertEquals
import org.junit.Test

class FreezeShownTest {
    private fun node(name: String, delay: Int) = Proxy(name, name, "", "Vless", delay, false)

    @Test
    fun `не отвечает не показывается у узла, который пингуется`() {
        val group = ProxyGroupState(
            name = "Auto",
            now = "a",
            selectable = true,
            proxies = listOf(node("alive", 120), node("silent", 0xffff), node("untested", 0), node("cut", 90)),
        )
        val marks = mapOf("alive" to "dead", "silent" to "dead", "untested" to "dead", "cut" to "frozen")

        assertEquals(
            mapOf("silent" to "dead", "untested" to "dead", "cut" to "frozen"),
            ServersState(groups = listOf(group), freezeMarks = marks).freeze,
        )
        assertEquals(marks, ServersState(freezeMarks = marks).freeze)
    }
}
