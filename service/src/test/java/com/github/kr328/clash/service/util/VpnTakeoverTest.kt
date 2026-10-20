package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.util.VpnTakeover.Poll
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnTakeoverTest {
    @Test
    fun `чужая VPN-сеть, которой не было при отзыве, — захват`() {
        assertEquals(Poll.TookOver, VpnTakeover.poll(stopped = true, running = false, before = setOf(1), now = setOf(1, 2)))
    }

    @Test
    fun `сторож — своей сети уже нет, любая VPN-сеть чужая`() {
        assertEquals(Poll.TookOver, VpnTakeover.poll(stopped = true, running = false, before = emptySet(), now = setOf(2)))
    }

    @Test
    fun `служба ещё останавливается — чужая сеть всё равно замечается`() {
        assertEquals(Poll.TookOver, VpnTakeover.poll(stopped = false, running = true, before = setOf(1), now = setOf(2)))
    }

    @Test
    fun `служба остановилась и запущена снова — человек включил наш VPN, это не захват`() {
        assertEquals(Poll.Restarted, VpnTakeover.poll(stopped = true, running = true, before = setOf(1), now = setOf(3)))
    }

    @Test
    fun `ничего нового — ждём`() {
        assertEquals(Poll.Wait, VpnTakeover.poll(stopped = true, running = false, before = setOf(1), now = emptySet()))
    }
}
