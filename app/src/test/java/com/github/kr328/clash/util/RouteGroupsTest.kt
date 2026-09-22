package com.github.kr328.clash.util

import com.github.kr328.clash.service.model.PanelGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteGroupsTest {
    private val names = listOf("Proxy", "Auto", "YouTube", "Telegram")

    private fun loads(
        nows: Map<String, String>,
        roots: List<String>,
        loaded: Int,
    ): List<String> {
        val calls = mutableListOf<String>()

        runBlocking {
            loadRouteGroups(names, roots, loaded, nows[names.getOrNull(loaded)]) { index ->
                calls += names[index]

                nows[names[index]]
            }
        }

        return calls
    }

    @Test
    fun `главная группа догружается вместе с вложенной, открытая на вкладке не перечитывается`() {
        val nows = mapOf("Proxy" to "Auto", "Auto" to "DE", "YouTube" to "NL")

        assertEquals(listOf("Proxy", "Auto"), loads(nows, roots = listOf("Proxy"), loaded = 2))
        assertEquals(listOf("Auto"), loads(nows, roots = listOf("Proxy"), loaded = 0))
    }

    @Test
    fun `открытая на вкладке группа в цепочке не запрашивается второй раз`() {
        val nows = mapOf("Proxy" to "Auto", "Auto" to "DE")

        assertEquals(listOf("Proxy"), loads(nows, roots = listOf("Proxy"), loaded = 1))
    }

    @Test
    fun `цикл выбора не зацикливает догрузку`() {
        val nows = mapOf("Proxy" to "Auto", "Auto" to "Proxy")

        assertEquals(listOf("Auto"), loads(nows, roots = listOf("Proxy"), loaded = 0))
    }

    @Test
    fun `каждая группа из списка запрашивается один раз`() {
        val nows = mapOf("Proxy" to "Auto", "Auto" to "DE", "YouTube" to "Proxy", "Telegram" to "DE")

        assertEquals(listOf("Proxy", "Auto", "Telegram"), loads(nows, roots = names, loaded = 2))
    }

    @Test
    fun `без главной группы ничего не догружается`() {
        assertEquals(emptyList<String>(), loads(emptyMap(), roots = emptyList(), loaded = 0))
        assertEquals(emptyList<String>(), loads(emptyMap(), roots = listOf("Gone"), loaded = 0))
    }

    @Test
    fun `без сохранённого выбора select-группа показывает первый узел, как выберет ядро`() {
        assertEquals("DE", offlineNow("select", null, listOf("DE", "NL")))
    }

    @Test
    fun `сохранённый выбор главнее первого узла`() {
        assertEquals("NL", offlineNow("select", "NL", listOf("DE", "NL")))
        assertEquals("NL", offlineNow("url-test", "NL", listOf("DE", "NL")))
    }

    @Test
    fun `автоматическая группа без выбора и пустая группа остаются без сервера`() {
        assertEquals("", offlineNow("url-test", null, listOf("DE", "NL")))
        assertEquals("", offlineNow("fallback", null, listOf("DE", "NL")))
        assertEquals("", offlineNow("select", null, emptyList()))
    }

    @Test
    fun `служебный узел первым выбирается, как у ядра, но в списке не показывается`() {
        val group = PanelGroup(name = "Proxy", type = "select", proxies = listOf("Осталось 5 дней", "DE", "NL"))

        val shown = offlineGroup(group, null) { it == "Осталось 5 дней" }

        assertEquals("Осталось 5 дней", shown.now)
        assertEquals(listOf("DE", "NL"), shown.proxies)
    }
}
