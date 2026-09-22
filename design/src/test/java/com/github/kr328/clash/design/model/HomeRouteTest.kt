package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState.Mode
import com.github.kr328.clash.design.compose.screen.ProxyGroupState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRouteTest {
    private fun node(name: String, delay: Int = 0, isGroup: Boolean = false) =
        Proxy(name = name, title = name, subtitle = "", type = "", delay = delay, isGroup = isGroup)

    private fun group(name: String, now: String, vararg proxies: Proxy) =
        ProxyGroupState(name = name, now = now, selectable = true, proxies = proxies.toList())

    private val germany = "🇩🇪 Germany"
    private val netherlands = "🇳🇱 Netherlands"

    private val template = listOf(
        group("Proxy", "Auto", node("Auto", 70, isGroup = true), node(netherlands, 95)),
        group("Auto", germany, node(germany, 80), node(netherlands, 95)),
        group("YouTube", netherlands, node("Proxy", 70, isGroup = true), node(netherlands, 95)),
        group("Telegram", germany, node(germany, 80)),
        group("Ads", "REJECT", node("REJECT"), node("DIRECT")),
        group("Local", "DIRECT", node("DIRECT"), node("Proxy", isGroup = true)),
    )

    @Test
    fun `в прямом режиме серверов нет, даже если группы из подписки подложены`() {
        assertEquals(HomeRoute.Direct, homeRoute(Mode.Direct, template, "Proxy", readOnly = true))
        assertEquals(HomeRoute.Direct, homeRoute(Mode.Direct, template, "Proxy", readOnly = false))
        assertEquals(HomeRoute.Direct, homeRoute(Mode.Rule, template, "Proxy", readOnly = true))
    }

    @Test
    fun `выбор внутри группы разворачивается до конечного сервера`() {
        assertEquals(
            HomeRoute.Server("Proxy", germany, 80),
            homeRoute(Mode.Rule, template, "Proxy", readOnly = false),
        )
        assertEquals(
            HomeRoute.Server("Telegram", germany, 80),
            homeRoute(Mode.Rule, template, "Telegram", readOnly = false),
        )
    }

    @Test
    fun `строка про главную группу, а не про открытую на вкладке`() {
        val names = template.map { it.name }

        assertEquals("Proxy", mainGroupOf(names, "Proxy"))
        assertEquals(
            HomeRoute.Server("Proxy", germany, 80),
            homeRoute(Mode.Rule, template, mainGroupOf(names, "Proxy"), readOnly = false),
        )
    }

    @Test
    fun `без главной группы от ядра или панели берётся первая группа`() {
        assertEquals("Auto", mainGroupOf(listOf("Auto", "Proxy"), null))
        assertEquals("Auto", mainGroupOf(listOf("Auto", "Proxy"), "Gone"))
        assertEquals(null, mainGroupOf(emptyList(), "Proxy"))
    }

    @Test
    fun `в глобальном режиме член GLOBAL без своей группы в списке не разворачивается`() {
        val global = listOf(group("GLOBAL", "Proxy", node("Proxy", 120, isGroup = true), node("DIRECT")))

        assertEquals(
            HomeRoute.Server("GLOBAL", "Proxy", 120),
            homeRoute(Mode.Global, global, "GLOBAL", readOnly = false),
        )
    }

    @Test
    fun `цикл выбора между группами не зависает`() {
        val cycle = listOf(
            group("Proxy", "A", node("A", 50, isGroup = true)),
            group("A", "Proxy", node("Proxy", 60, isGroup = true)),
        )

        assertEquals(
            HomeRoute.Server("Proxy", "Proxy", 60),
            homeRoute(Mode.Rule, cycle, "Proxy", readOnly = false),
        )
    }

    @Test
    fun `DIRECT показывается без пинга, REJECT отдельно`() {
        assertEquals(HomeRoute.Bypass("Local"), homeRoute(Mode.Rule, template, "Local", readOnly = false))
        assertEquals(HomeRoute.Blocked("Ads", "REJECT"), homeRoute(Mode.Rule, template, "Ads", readOnly = false))
    }

    @Test
    fun `не загруженная группа не выдаёт чужой сервер`() {
        val cold = listOf(
            group("Proxy", "Auto", node("Auto", 70, isGroup = true)),
            group("Auto", ""),
        )

        assertEquals(HomeRoute.Server("Proxy", "Auto", 70), homeRoute(Mode.Rule, cold, "Proxy", false))
        assertEquals(HomeRoute.Server("Auto", null, null), homeRoute(Mode.Rule, cold, "Auto", false))
        assertEquals(HomeRoute.None, homeRoute(Mode.Rule, cold, "Gone", false))
        assertEquals(HomeRoute.None, homeRoute(Mode.Rule, cold, null, false))
    }

    @Test
    fun `остальные группы идут под главной, REJECT скрыт, DIRECT без пинга`() {
        assertEquals(
            listOf(
                HomeRoute.Server("Auto", germany, 80),
                HomeRoute.Server("YouTube", netherlands, 95),
                HomeRoute.Server("Telegram", germany, 80),
                HomeRoute.Bypass("Local"),
            ),
            homeExtras(Mode.Rule, template, "Proxy", readOnly = false),
        )
    }

    @Test
    fun `в прямом и глобальном режиме дополнительных строк нет`() {
        val global = listOf(group("GLOBAL", "Proxy", node("Proxy", 120, isGroup = true)))

        assertEquals(emptyList<HomeRoute>(), homeExtras(Mode.Direct, template, "Proxy", readOnly = true))
        assertEquals(emptyList<HomeRoute>(), homeExtras(Mode.Rule, template, "Proxy", readOnly = true))
        assertEquals(emptyList<HomeRoute>(), homeExtras(Mode.Global, global, "GLOBAL", readOnly = false))
    }
}
