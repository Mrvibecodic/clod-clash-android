package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.AccessControlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunAppsTest {
    private val self = "app.self"

    // Установленные пакеты с UID; себя служба видит всегда.
    private fun uids(vararg apps: Pair<String, Int>): (String) -> Int? {
        val map = apps.toMap() + (self to 1)

        return { map[it] }
    }

    @Test
    fun `все приложения — исключения подписки среди установленных`() {
        val apps = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), setOf("bank", "gov", self), self, uids("bank" to 10))

        assertEquals(TunApps(emptyMap(), mapOf("bank" to 10)), apps)
    }

    @Test
    fun `все приложения — установленные включения подписки дают белый список`() {
        val apps = tunApps(AccessControlMode.AcceptAll, emptySet(), setOf("chat", "absent"), setOf("bank"), self, uids("chat" to 11, "bank" to 10))

        assertEquals(TunApps(mapOf("chat" to 11, self to 1), emptyMap()), apps)
    }

    @Test
    fun `все приложения — неустановленные включения не отменяют исключений`() {
        val apps = tunApps(AccessControlMode.AcceptAll, emptySet(), setOf("absent"), setOf("bank"), self, uids("bank" to 10))

        assertEquals(TunApps(emptyMap(), mapOf("bank" to 10)), apps)
    }

    @Test
    fun `разрешить выбранные — выбор и включения, исключения не применяются`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, setOf("browser", "gone"), setOf("chat"), setOf("browser"), self, uids("browser" to 12, "chat" to 11))

        assertEquals(TunApps(mapOf("browser" to 12, "chat" to 11, self to 1), emptyMap()), apps)
    }

    @Test
    fun `запретить выбранные — выбор и исключения, без себя`() {
        val apps = tunApps(AccessControlMode.DenySelected, setOf("game", self), emptySet(), setOf("bank", "gone"), self, uids("game" to 13, "bank" to 10))

        assertEquals(TunApps(emptyMap(), mapOf("game" to 13, "bank" to 10)), apps)
    }

    @Test
    fun `установка приложения из списка исключений требует пересборки`() {
        val before = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), setOf("bank"), self, uids())
        val now = uids("bank" to 10)
        val after = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), setOf("bank"), self, now)

        assertTrue(tunAppsChanged(before, after, now))
    }

    @Test
    fun `удаление приложения из применённого состава пересборки не требует`() {
        val before = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), setOf("bank"), self, uids("bank" to 10))
        val now = uids()
        val after = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), setOf("bank"), self, now)

        assertFalse(tunAppsChanged(before, after, now))
    }

    @Test
    fun `переустановка приложения с новым UID требует пересборки`() {
        val before = tunApps(AccessControlMode.AcceptSelected, setOf("browser"), emptySet(), emptySet(), self, uids("browser" to 12))
        val now = uids("browser" to 42)
        val after = tunApps(AccessControlMode.AcceptSelected, setOf("browser"), emptySet(), emptySet(), self, now)

        assertTrue(tunAppsChanged(before, after, now))
    }

    @Test
    fun `удаление последнего включения переводит на исключения — пересборка`() {
        val before = tunApps(AccessControlMode.AcceptAll, emptySet(), setOf("chat"), setOf("bank"), self, uids("chat" to 11, "bank" to 10))
        val now = uids("bank" to 10)
        val after = tunApps(AccessControlMode.AcceptAll, emptySet(), setOf("chat"), setOf("bank"), self, now)

        assertEquals(TunApps(emptyMap(), mapOf("bank" to 10)), after)
        assertTrue(tunAppsChanged(before, after, now))
    }

    @Test
    fun `подписка убрала установленное приложение из исключений — пересборка`() {
        val now = uids("bank" to 10)
        val before = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), setOf("bank"), self, now)
        val after = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), emptySet(), self, now)

        assertTrue(tunAppsChanged(before, after, now))
    }

    @Test
    fun `тот же состав — без пересборки`() {
        val now = uids("bank" to 10, "chat" to 11)
        val before = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), setOf("bank"), self, now)
        val after = tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), setOf("bank", "absent"), self, now)

        assertFalse(tunAppsChanged(before, after, now))
    }
}
