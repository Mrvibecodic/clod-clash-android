package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.TunPrefs
import kotlinx.serialization.json.Json
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
    fun `разрешить выбранные — пустой выбор без списков подписки пускает только себя`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, emptySet(), emptySet(), emptySet(), self, uids("browser" to 12))

        assertEquals(TunApps(mapOf(self to 1), emptyMap()), apps)
    }

    @Test
    fun `разрешить выбранные — пустой выбор и только исключения подписки — все, кроме них`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, emptySet(), setOf("absent"), setOf("bank", self), self, uids("bank" to 10))

        assertEquals(TunApps(emptyMap(), mapOf("bank" to 10)), apps)
    }

    @Test
    fun `разрешить выбранные — отмеченное и удалённое приложение выбором не считается`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, setOf("gone"), emptySet(), setOf("bank", self), self, uids("bank" to 10))

        assertEquals(TunApps(emptyMap(), mapOf("bank" to 10)), apps)
    }

    @Test
    fun `разрешить выбранные — выбор отменяет исключения подписки`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, setOf("browser"), emptySet(), setOf("bank"), self, uids("browser" to 12, "bank" to 10))

        assertEquals(TunApps(mapOf("browser" to 12, self to 1), emptyMap()), apps)
    }

    @Test
    fun `разрешить выбранные — включения подписки при пустом выборе`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, emptySet(), setOf("chat"), setOf("bank"), self, uids("chat" to 11, "bank" to 10))

        assertEquals(TunApps(mapOf("chat" to 11, self to 1), emptyMap()), apps)
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

    private fun onlySelf(apps: TunApps) = onlySelfThroughTunnel(apps.allowed.keys, self)

    @Test
    fun `только себя — разрешить выбранные без выбора и без списков подписки`() {
        assertTrue(onlySelf(tunApps(AccessControlMode.AcceptSelected, emptySet(), emptySet(), emptySet(), self, uids("browser" to 12))))
    }

    @Test
    fun `только себя — отмечены лишь удалённые приложения`() {
        assertTrue(onlySelf(tunApps(AccessControlMode.AcceptSelected, setOf("gone"), emptySet(), emptySet(), self, uids("browser" to 12))))
    }

    @Test
    fun `только себя — включения подписки не установлены`() {
        assertTrue(onlySelf(tunApps(AccessControlMode.AcceptSelected, emptySet(), setOf("absent"), emptySet(), self, uids())))
    }

    @Test
    fun `не только себя — исключения подписки пускают остальных`() {
        assertFalse(onlySelf(tunApps(AccessControlMode.AcceptSelected, emptySet(), emptySet(), setOf("absent"), self, uids())))
    }

    @Test
    fun `не только себя — установленное включение подписки`() {
        assertFalse(onlySelf(tunApps(AccessControlMode.AcceptSelected, emptySet(), setOf("chat"), emptySet(), self, uids("chat" to 11))))
    }

    @Test
    fun `не только себя — отмечено установленное приложение`() {
        assertFalse(onlySelf(tunApps(AccessControlMode.AcceptSelected, setOf("browser"), emptySet(), emptySet(), self, uids("browser" to 12))))
    }

    @Test
    fun `не только себя — запретить выбранные и все приложения`() {
        assertFalse(onlySelf(tunApps(AccessControlMode.DenySelected, emptySet(), emptySet(), emptySet(), self, uids())))
        assertFalse(onlySelf(tunApps(AccessControlMode.AcceptAll, emptySet(), emptySet(), emptySet(), self, uids())))
    }

    @Test
    fun `пустой принятый белый список — это все приложения, а не никто`() {
        assertFalse(onlySelfThroughTunnel(emptyList(), self))
    }

    @Test
    fun `списки подписки берутся из её настроек туннеля`() {
        val prefs = TunPrefs(includePackages = listOf("chat"), excludePackages = listOf("bank"))
        val now = uids("chat" to 11, "bank" to 10)

        assertEquals(
            tunApps(AccessControlMode.AcceptSelected, emptySet(), setOf("chat"), setOf("bank"), self, now),
            tunApps(AccessControlMode.AcceptSelected, emptySet(), prefs, self, now),
        )
    }

    @Test
    fun `режим провайдера — разрешить выбранные без отметок и без списков пускают все приложения`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, emptySet(), emptySet(), emptySet(), self, uids("browser" to 12), providerAllApps = true)

        assertEquals(TunApps(emptyMap(), emptyMap()), apps)
        assertFalse(onlySelf(apps))
    }

    @Test
    fun `режим провайдера — отмечены лишь удалённые приложения, режим действует`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, setOf("gone"), emptySet(), emptySet(), self, uids("browser" to 12), providerAllApps = true)

        assertEquals(TunApps(emptyMap(), emptyMap()), apps)
    }

    @Test
    fun `режим провайдера — отмечено установленное приложение, режим не действует`() {
        val apps = tunApps(AccessControlMode.AcceptSelected, setOf("browser"), emptySet(), emptySet(), self, uids("browser" to 12), providerAllApps = true)

        assertEquals(TunApps(mapOf("browser" to 12, self to 1), emptyMap()), apps)
    }

    @Test
    fun `режим провайдера — списки подписки работают как в режиме все приложения`() {
        val now = uids("chat" to 11, "bank" to 10)
        val lists = listOf(
            emptySet<String>() to emptySet(),
            setOf("chat") to setOf("bank"),
            emptySet<String>() to setOf("bank"),
            setOf("absent") to emptySet(),
            setOf("absent") to setOf("bank"),
        )

        for ((include, exclude) in lists) {
            for (selected in listOf(emptySet(), setOf("gone"))) {
                assertEquals(
                    "$include / $exclude / $selected",
                    tunApps(AccessControlMode.AcceptAll, emptySet(), include, exclude, self, now),
                    tunApps(AccessControlMode.AcceptSelected, selected, include, exclude, self, now, providerAllApps = true),
                )
            }
        }
    }

    @Test
    fun `режим провайдера — режимы, выбранные человеком, не трогает`() {
        val now = uids("game" to 13, "bank" to 10)

        for (mode in listOf(AccessControlMode.AcceptAll, AccessControlMode.DenySelected)) {
            for (selected in listOf(emptySet(), setOf("game"))) {
                assertEquals(
                    tunApps(mode, selected, emptySet(), setOf("bank"), self, now),
                    tunApps(mode, selected, emptySet(), setOf("bank"), self, now, providerAllApps = true),
                )
            }
        }
    }

    @Test
    fun `режим провайдера берётся из настроек туннеля подписки`() {
        val now = uids("browser" to 12)

        assertEquals(
            TunApps(emptyMap(), emptyMap()),
            tunApps(AccessControlMode.AcceptSelected, emptySet(), TunPrefs(allApps = true), self, now),
        )
        assertEquals(
            TunApps(mapOf(self to 1), emptyMap()),
            tunApps(AccessControlMode.AcceptSelected, emptySet(), TunPrefs(), self, now),
        )
    }

    @Test
    fun `отпечаток доступа — по действующему режиму`() {
        val now = uids("browser" to 12)
        val provider = TunPrefs(allApps = true)

        assertEquals(
            tunAccessFingerprint(AccessControlMode.AcceptAll, emptySet(), provider, now),
            tunAccessFingerprint(AccessControlMode.AcceptSelected, setOf("gone"), provider, now),
        )
        assertEquals("AcceptSelected:browser", tunAccessFingerprint(AccessControlMode.AcceptSelected, setOf("browser"), provider, now))
        assertEquals("AcceptSelected:", tunAccessFingerprint(AccessControlMode.AcceptSelected, emptySet(), TunPrefs(), now))
        assertEquals("DenySelected:", tunAccessFingerprint(AccessControlMode.DenySelected, emptySet(), provider, now))
    }

    @Test
    fun `режим провайдера читается тем именем, что пишет ядро, а у старого файла его нет`() {
        val json = Json { ignoreUnknownKeys = true }

        assertTrue(json.decodeFromString(TunPrefs.serializer(), """{"allApps":true}""").allApps)
        assertFalse(json.decodeFromString(TunPrefs.serializer(), """{"excludePackages":["bank"]}""").allApps)
    }
}
