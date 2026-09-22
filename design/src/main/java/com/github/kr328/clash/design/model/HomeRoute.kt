package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.compose.screen.ProxyGroupState

private const val DIRECT_SELECTION = "DIRECT"

sealed interface HomeRoute {
    data object None : HomeRoute

    data object Direct : HomeRoute

    data class Server(val group: String, val title: String?, val delay: Int?) : HomeRoute

    data class Bypass(val group: String) : HomeRoute

    data class Blocked(val group: String, val title: String) : HomeRoute
}

fun mainGroupOf(names: List<String>, main: String?): String? =
    main?.takeIf { it in names } ?: names.firstOrNull()

fun homeRoute(
    mode: TunnelState.Mode,
    groups: List<ProxyGroupState>,
    group: String?,
    readOnly: Boolean,
): HomeRoute {
    if (mode == TunnelState.Mode.Direct || readOnly) return HomeRoute.Direct

    val byName = groups.associateBy { it.name }
    val root = byName[group] ?: return HomeRoute.None

    if (root.now.isBlank()) return HomeRoute.Server(root.name, null, null)

    val seen = mutableSetOf(root.name)
    var current = root

    while (true) {
        val now = current.now

        if (now in BLOCKING_SELECTIONS) return HomeRoute.Blocked(root.name, now)
        if (now == DIRECT_SELECTION) return HomeRoute.Bypass(root.name)

        val next = byName[now]

        if (next == null || next.now.isBlank() || !seen.add(now)) {
            val leaf = current.proxies.firstOrNull { it.name == now }

            return HomeRoute.Server(root.name, leaf?.title ?: now, leaf?.delay)
        }

        current = next
    }
}
