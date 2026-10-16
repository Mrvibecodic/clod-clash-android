package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.compose.screen.ProxyGroupState

private const val DIRECT_SELECTION = "DIRECT"

sealed interface HomeRoute {
    data object None : HomeRoute

    data object Direct : HomeRoute

    // name — имя конечного узла, как его знает ядро: по нему ищутся пометки;
    // via — группа, которая выбрала этот сервер, если это не сама группа строки
    data class Server(
        val group: String,
        val title: String?,
        val delay: Int?,
        val name: String? = title,
        val via: String? = null,
    ) : HomeRoute

    data class Bypass(val group: String) : HomeRoute

    data class Blocked(val group: String) : HomeRoute

    // Все серверы на пути — только для мобильной сети, а сеть не мобильная:
    // группа отказывает, как на вкладке «Серверы»
    data class MobileOnly(val group: String) : HomeRoute
}

fun mainGroupOf(names: List<String>, main: String?): String? =
    main?.takeIf { it in names } ?: names.firstOrNull()

fun homeExtras(
    mode: TunnelState.Mode,
    groups: List<ProxyGroupState>,
    main: String?,
    readOnly: Boolean,
    hides: (String) -> Boolean = { false },
): List<HomeRoute> = groups
    .filter { it.name != main }
    .map { homeRoute(mode, groups, it.name, readOnly, hides) }
    .filter { it is HomeRoute.Server || it is HomeRoute.Bypass }

fun homeRoute(
    mode: TunnelState.Mode,
    groups: List<ProxyGroupState>,
    group: String?,
    readOnly: Boolean,
    // Узел-заглушка панели (лимит устройств, истёкшая): трафик через него
    // никуда не идёт — путь не «сервер», а блокировка
    hides: (String) -> Boolean = { false },
): HomeRoute {
    if (mode == TunnelState.Mode.Direct || readOnly) return HomeRoute.Direct

    val byName = groups.associateBy { it.name }
    val root = byName[group] ?: return HomeRoute.None

    if (root.allHidden) return HomeRoute.MobileOnly(root.name)
    if (root.now.isBlank()) return HomeRoute.Server(root.name, null, null)

    val seen = mutableSetOf(root.name)
    var current = root

    while (true) {
        val now = current.now

        if (now in BLOCKING_SELECTIONS) return HomeRoute.Blocked(root.name)
        if (now == DIRECT_SELECTION) return HomeRoute.Bypass(root.name)

        val next = byName[now]

        if (next?.allHidden == true) return HomeRoute.MobileOnly(root.name)

        if (next == null || next.now.isBlank() || !seen.add(now)) {
            val leaf = current.proxies.firstOrNull { it.name == now }
            // Группа, чей выбор дальше не виден (скрыта в шаблоне или ещё не
            // загружена), — сервер, на котором она стоит, если он известен
            val inner = leaf?.current?.takeIf { it.isNotEmpty() }

            if (inner != null) {
                if (inner in BLOCKING_SELECTIONS || hides(inner)) return HomeRoute.Blocked(root.name)
                if (inner == DIRECT_SELECTION) return HomeRoute.Bypass(root.name)

                return HomeRoute.Server(
                    root.name,
                    leaf.currentTitle.ifEmpty { inner },
                    leaf.delay,
                    inner,
                    via = leaf.currentGroup.ifEmpty { now },
                )
            }

            if (hides(now)) return HomeRoute.Blocked(root.name)

            // Строка — группа без известного сервера: показывается она сама, без «через»
            val via = current.name.takeIf { next == null && current !== root && leaf?.isGroup != true }

            return HomeRoute.Server(root.name, leaf?.title ?: now, leaf?.delay, now, via)
        }

        current = next
    }
}
