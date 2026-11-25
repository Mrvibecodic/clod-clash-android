package com.github.kr328.clash.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDelaysRestartTest {
    private fun changed(startNetwork: String?, startKey: String?, lost: Boolean, active: String?, key: String?) =
        OfflineDelays.networkChanged(startNetwork, startKey, lost, active, key)

    @Test
    fun `та же сеть без потерь — замер не перезапускается`() {
        // Первые колбэки при регистрации видят сеть на старте
        assertFalse(changed("wifi-1", "home", lost = false, active = "wifi-1", key = "home"))
    }

    @Test
    fun `Wi-Fi сменился на SIM — перезапуск`() {
        assertTrue(changed("wifi-1", "home", lost = false, active = "sim-1", key = "operator"))
    }

    @Test
    fun `сеть пропала и вернулась та же — перезапуск`() {
        // Пробы в разрыве уже упали, хотя ключ тот же
        assertTrue(changed("wifi-1", "home", lost = true, active = "wifi-2", key = "home"))
        assertTrue(changed("wifi-1", "home", lost = true, active = "wifi-1", key = "home"))
    }

    @Test
    fun `сети нет — ждать новую`() {
        assertFalse(changed("wifi-1", "home", lost = true, active = null, key = null))
    }

    @Test
    fun `замер начат без сети, сеть появилась — перезапуск`() {
        assertTrue(changed(null, null, lost = false, active = "wifi-1", key = "home"))
    }

    @Test
    fun `та же сеть с другим ключом — перезапуск`() {
        assertTrue(changed("wifi-1", "home", lost = false, active = "wifi-1", key = "home-new-dns"))
    }
}
