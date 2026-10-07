package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxySubtitleTest {
    private fun proxy(subtitle: String, type: String = "Vless") =
        Proxy(name = "node", title = "node", subtitle = subtitle, type = type, delay = 0, isGroup = false)

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
}
