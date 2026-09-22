package com.github.kr328.clash.util

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
