package com.github.kr328.clash.service.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PanelInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(text: String) = json.decodeFromString(PanelInfo.serializer(), text)

    @Test
    fun `пустой объект читается как пустая подписка`() {
        val info = decode("{}")

        assertTrue(info.isEmpty)
        assertEquals("", info.title)
        assertEquals(0, info.hwidMaxDevices)
        assertFalse(info.noServers)
    }

    @Test
    fun `заголовок, которого приложение ещё не знает, не роняет разбор`() {
        val info = decode("""{"title":"Провайдер","clodSomethingNew":42}""")

        assertEquals("Провайдер", info.title)
    }

    @Test
    fun `молчание панели о напоминаниях — это null, а не пустота`() {
        val info = decode("{}")

        assertNull(info.notifyExpireDays)
        assertNull(info.notifyTrafficPercent)
    }

    @Test
    fun `выключенные панелью напоминания читаются как пустой список`() {
        val info = decode("""{"notifyExpireDays":[],"notifyTrafficPercent":[]}""")

        assertEquals(emptyList<Int>(), info.notifyExpireDays)
        assertEquals(emptyList<Int>(), info.notifyTrafficPercent)
    }

    @Test
    fun `границы пинга читаются теми же именами, что пишет ядро, а у старого файла их нет`() {
        val info = decode("""{"pingFast":150,"pingMedium":600}""")

        assertEquals(150, info.pingFast)
        assertEquals(600, info.pingMedium)
        assertEquals(0, decode("""{"disablePing":true}""").pingFast)
        assertEquals(0, decode("""{"disablePing":true}""").pingMedium)
    }

    @Test
    fun `состав групп переживает разбор`() {
        val info = decode(
            """{"groups":[{"name":"🇳🇱 Нидерланды","type":"url-test","proxies":["A","B"]}]}""",
        )

        assertEquals(1, info.groups.size)
        assertEquals("🇳🇱 Нидерланды", info.groups[0].name)
        assertEquals(listOf("A", "B"), info.groups[0].proxies)
    }

    @Test
    fun `главная группа читается из panel json, а у старого файла её нет`() {
        assertEquals("Proxy", decode("""{"groups":[{"name":"Auto"},{"name":"Proxy"}],"main":"Proxy"}""").main)
        assertNull(decode("""{"groups":[{"name":"Auto"},{"name":"Proxy"}]}""").main)
    }

    @Test
    fun `ссылки провайдера читаются теми же именами, что пишет ядро`() {
        val info = decode(
            """{"portalUrl":"https://provider.example/cabinet",""" +
                """"supportUrl":"https://t.me/provider_support",""" +
                """"botUrl":"tg://resolve?domain=provider_bot",""" +
                """"monitorUrl":"https://status.provider.example",""" +
                """"guideUrl":"https://provider.example/help"}""",
        )

        assertEquals("https://provider.example/cabinet", info.portalUrl)
        assertEquals("https://t.me/provider_support", info.supportUrl)
        assertEquals("tg://resolve?domain=provider_bot", info.botUrl)
        assertEquals("https://status.provider.example", info.monitorUrl)
        assertEquals("https://provider.example/help", info.guideUrl)
    }

    @Test
    fun `подписка без ссылок отдаёт пустые строки, а не null`() {
        val info = decode("{}")

        assertEquals("", info.botUrl)
        assertEquals("", info.monitorUrl)
        assertEquals("", info.guideUrl)
    }

    private fun measuredJustNow() = System.currentTimeMillis() / 1000 - 60

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    @Test
    fun `без измерения поправки нет`() {
        assertEquals(0L, PanelInfo().clockSkewMillis())
        assertEquals(0L, PanelInfo(clockSkew = 120).clockSkewMillis())
        assertEquals(0L, PanelInfo(clockSkewAt = nowSeconds()).clockSkewMillis())
    }

    @Test
    fun `свежее измерение применяется в миллисекундах`() {
        val info = PanelInfo(clockSkew = 120, clockSkewAt = measuredJustNow())

        assertEquals(120_000L, info.clockSkewMillis())
    }

    @Test
    fun `отставание часов устройства тоже поправка`() {
        val info = PanelInfo(clockSkew = -90, clockSkewAt = measuredJustNow())

        assertEquals(-90_000L, info.clockSkewMillis())
    }

    @Test
    fun `измерение старше месяца выбрасывается`() {
        val monthAndADay = TimeUnit.DAYS.toSeconds(31)
        val info = PanelInfo(clockSkew = 120, clockSkewAt = nowSeconds() - monthAndADay)

        assertEquals(0L, info.clockSkewMillis())
    }

    @Test
    fun `измерение из будущего выбрасывается`() {
        val info = PanelInfo(clockSkew = 120, clockSkewAt = nowSeconds() + TimeUnit.DAYS.toSeconds(2))

        assertEquals(0L, info.clockSkewMillis())
    }

    @Test
    fun `одного логотипа хватает, чтобы подписка перестала быть пустой`() {
        assertFalse(PanelInfo(logoFile = "logo.png").isEmpty)
        assertFalse(PanelInfo(title = "Провайдер").isEmpty)
        assertFalse(PanelInfo(portalUrl = "https://example.org").isEmpty)
        assertFalse(PanelInfo(groups = listOf(PanelGroup(name = "Все"))).isEmpty)
    }

    @Test
    fun `служебные поля пустоту не отменяют`() {
        assertTrue(PanelInfo(refillDate = 1_700_000_000, hwidMaxDevices = 3).isEmpty)
    }

    @Test
    fun `служебный узел панели скрыт, обычный виден`() {
        val info = PanelInfo(sentinels = listOf("заглушка"))

        assertTrue(info.hides("заглушка"))
        assertFalse(info.hides("Нидерланды 01"))
    }

    @Test
    fun `при показе заглушек панель ничего не скрывает`() {
        val info = PanelInfo(sentinels = emptyList())

        assertFalse(info.hides("заглушка"))
    }
}
