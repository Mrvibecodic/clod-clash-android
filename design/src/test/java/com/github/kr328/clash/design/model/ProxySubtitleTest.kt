package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxySubtitleTest {
    private fun proxy(
        subtitle: String,
        type: String = "Vless",
        name: String = "node",
        title: String = name,
        isGroup: Boolean = false,
    ) = Proxy(name = name, title = title, subtitle = subtitle, type = type, delay = 0, isGroup = isGroup)

    @Test
    fun `подпись ядра — как есть`() {
        assertEquals("VLESS RAW (TCP) · Reality", proxySubtitle(proxy("VLESS RAW (TCP) · Reality"), null, "Trojan RAW (TCP) · TLS"))
    }

    @Test
    fun `в списке без ядра подпись — протокол из подписки`() {
        assertEquals("TUIC (UDP)", proxySubtitle(proxy("", type = ""), null, "TUIC (UDP)"))
    }

    @Test
    fun `подпись из подписки не ложится на строку ядра`() {
        // Одноимённый узел провайдера: ядро дало свою подпись или тип
        assertEquals("Vless", proxySubtitle(proxy("Vless"), null, "VLESS RAW (TCP)"))
        assertEquals("Selector", proxySubtitle(proxy("Selector", type = "Selector"), null, "VLESS RAW (TCP)"))
    }

    @Test
    fun `описание сервера важнее протокола`() {
        assertEquals("Быстрый", proxySubtitle(proxy("", type = ""), "Быстрый", "VLESS RAW (TCP)"))
        assertEquals("VLESS RAW (TCP)", proxySubtitle(proxy("", type = ""), " ", "VLESS RAW (TCP)"))
    }

    @Test
    fun `подпись по шаблону подписки не заменяется`() {
        assertEquals("NL", proxySubtitle(proxy("NL"), null, "VLESS RAW (TCP)"))
    }

    @Test
    fun `скрытие подписи убирает протокол и транспорт у сервера`() {
        assertEquals("", proxySubtitle(proxy("VLESS RAW (TCP) · Reality"), null, null, hideBadges = true))
        assertEquals("", proxySubtitle(proxy("Vless"), null, "VLESS RAW (TCP)", hideBadges = true))
        // Без туннеля — протокол из подписки тоже не подставляется
        assertEquals("", proxySubtitle(proxy("", type = ""), null, "TUIC (UDP)", hideBadges = true))
    }

    @Test
    fun `скрытие подписи не трогает описание сервера`() {
        assertEquals("Быстрый", proxySubtitle(proxy("VLESS RAW (TCP)"), "Быстрый", null, hideBadges = true))
        assertEquals("Быстрый", proxySubtitle(proxy("", type = ""), "Быстрый", "TUIC (UDP)", hideBadges = true))
    }

    @Test
    fun `скрытие подписи не трогает подпись по шаблону подписки`() {
        // ui-subtitle-pattern вырезал «NL» из имени — заголовок короче имени
        assertEquals("NL", proxySubtitle(proxy("NL", name = "Амстердам NL", title = "Амстердам"), null, null, hideBadges = true))
    }

    @Test
    fun `пробелы по краям имени не принимаются за шаблон подписки`() {
        // Ядро обрезает пробелы у имени — заголовок короче имени и без шаблона
        assertEquals("", proxySubtitle(proxy("VLESS RAW (TCP)", name = " node ", title = "node"), null, null, hideBadges = true))
    }

    @Test
    fun `скрытие подписи не трогает тип группы`() {
        assertEquals("Selector", proxySubtitle(proxy("Selector", type = "Selector", isGroup = true), null, null, hideBadges = true))
    }
}
