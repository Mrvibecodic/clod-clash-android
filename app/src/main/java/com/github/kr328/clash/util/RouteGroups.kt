package com.github.kr328.clash.util

import com.github.kr328.clash.service.model.PanelGroup

internal suspend fun loadRouteGroups(
    names: List<String>,
    roots: List<String>,
    loaded: Int,
    loadedNow: String?,
    load: suspend (Int) -> String?,
) {
    val pending = ArrayDeque(roots.map(names::indexOf).filter { it >= 0 })
    val visited = mutableSetOf<Int>()

    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()

        if (!visited.add(index)) continue

        val now = if (index == loaded) loadedNow else load(index)
        val next = names.indexOf(now)

        if (next >= 0) pending.addLast(next)
    }
}

internal fun offlineNow(type: String, saved: String?, proxies: List<String>): String =
    saved ?: if (type == "select") proxies.firstOrNull().orEmpty() else ""

// allHidden — в группе были серверы, и все они только для мобильной сети
internal data class OfflineGroup(val now: String, val proxies: List<String>, val allHidden: Boolean = false)

private val BUILTIN = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")

// Все серверы группы только для мобильной сети вне неё: у ядра она отказывает
private fun PanelGroup.allGone(gone: (String) -> Boolean): Boolean = proxies.isNotEmpty() && proxies.all(gone)

// hides — не показывать (заглушки панели: ядро их выбирает); gone — серверов
// нет и у ядра (только для мобильной сети вне неё); groups — группы подписки.
// Скрытый выбор select-группы — сохранённый или, без него, первый член —
// как у ядра, заменяется первым видимым сервером, затем группой, где видно
// хоть что-то, и никогда DIRECT; иначе группа отказывает.
internal fun offlineGroup(
    group: PanelGroup,
    saved: String?,
    gone: (String) -> Boolean = { false },
    groups: List<PanelGroup> = emptyList(),
    hides: (String) -> Boolean,
): OfflineGroup {
    val left = group.proxies.filterNot(gone)
    val groupOf = { name: String -> groups.firstOrNull { it.name == name } }
    val target = saved ?: group.proxies.firstOrNull()
    val hidden = target != null && (gone(target) || groupOf(target)?.allGone(gone) == true)

    val now = when {
        // Автоматическая группа со скрытым закреплением выберет сама
        group.type != "select" -> if (hidden) "" else offlineNow(group.type, saved, left)
        hidden -> left.firstOrNull { it !in BUILTIN && groupOf(it) == null }
            ?: left.firstOrNull { groupOf(it)?.allGone(gone) == false }
            ?: ""
        else -> offlineNow(group.type, saved, left)
    }

    return OfflineGroup(now, left.filterNot(hides).distinct(), group.allGone(gone))
}
