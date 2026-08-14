package com.github.kr328.clash.service.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * `panel.json` пишет ядро, а читает приложение — то есть формат живёт в двух
 * языках сразу. Здесь проверяется та часть договора, которую видно со стороны
 * Kotlin: старый файл без новых полей должен читаться, а не ронять разбор,
 * и поправка часов не должна применяться, когда ей верить нельзя.
 */
class PanelInfoTest {
    // Тот же разбор, что в `service/util/Panel.kt`.
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
        // Ядро может уйти вперёд приложения: обновилось оно вместе с APK, но
        // старый `panel.json` мог остаться от сборки посвежее — например,
        // после отката. Неизвестное поле должно молча игнорироваться.
        val info = decode("""{"title":"Провайдер","clodSomethingNew":42}""")

        assertEquals("Провайдер", info.title)
    }

    @Test
    fun `молчание панели о напоминаниях и режиме — это null, а не пустота`() {
        val info = decode("{}")

        // null означает «панель не сказала, берём умолчания», пустой список —
        // «панель выключила». Значение-заглушка стёрло бы эту разницу.
        assertNull(info.notifyExpireDays)
        assertNull(info.notifyTrafficPercent)
        assertNull(info.lockMode)
    }

    @Test
    fun `выключенные панелью напоминания читаются как пустой список`() {
        val info = decode("""{"notifyExpireDays":[],"notifyTrafficPercent":[]}""")

        assertEquals(emptyList<Int>(), info.notifyExpireDays)
        assertEquals(emptyList<Int>(), info.notifyTrafficPercent)
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
    fun `ссылки провайдера читаются теми же именами, что пишет ядро`() {
        // Имена полей — договор с Go (`native/config/panel/panel.go`).
        // Разъедутся — строки в настройках молча исчезнут, и виноватым будет
        // выглядеть провайдер, а не опечатка в имени поля.
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
        // Экран настроек решает по `isNotBlank()`: пустая строка означает
        // «заголовка не было», и блока ссылок тогда нет вовсе.
        val info = decode("{}")

        assertEquals("", info.botUrl)
        assertEquals("", info.monitorUrl)
        assertEquals("", info.guideUrl)
    }

    // --- поправка часов ---

    /**
     * Часы читаются с запасом в минуту назад намеренно. Прод сравнивает возраст
     * измерения с нулём (`age in 0..MAX`), а `clockSkewMillis()` читает часы
     * ВТОРОЙ раз — шаг NTP назад между двумя чтениями дал бы `age == -1`
     * и невоспроизводимо красный CI.
     */
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
        // Отрицательный возраст значит, что часы устройства уехали назад
        // ПОД измерением — верить старой поправке в этот момент нельзя.
        val info = PanelInfo(clockSkew = 120, clockSkewAt = nowSeconds() + TimeUnit.DAYS.toSeconds(2))

        assertEquals(0L, info.clockSkewMillis())
    }

    // --- «показывать ли карточку провайдера» ---

    @Test
    fun `одного логотипа хватает, чтобы подписка перестала быть пустой`() {
        assertFalse(PanelInfo(logoFile = "logo.png").isEmpty)
        assertFalse(PanelInfo(title = "Провайдер").isEmpty)
        assertFalse(PanelInfo(portalUrl = "https://example.org").isEmpty)
        assertFalse(PanelInfo(groups = listOf(PanelGroup(name = "Все"))).isEmpty)
    }

    @Test
    fun `служебные поля пустоту не отменяют`() {
        // Срок обновления трафика и лимит устройств показывать не в чем:
        // карточка провайдера строится из названия, логотипа и объявлений.
        assertTrue(PanelInfo(refillDate = 1_700_000_000, hwidMaxDevices = 3).isEmpty)
    }
}
