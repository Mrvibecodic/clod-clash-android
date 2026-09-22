package com.github.kr328.clash.util

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
}
