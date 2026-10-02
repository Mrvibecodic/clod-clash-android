package com.github.kr328.clash.util

// Судьба каждой записи копии до загрузки: адрес негоден, такая подписка уже есть или
// запись идёт на загрузку. Уже имеющиеся — не провал восстановления; повтор адреса
// внутри копии — одна подписка, активная, если активна любая из записей.
internal data class RestorePlan(
    val load: List<ProfileImports.Item>,
    val present: Int,
    val rejected: List<ProfileImports.Item>,
)

internal fun planRestore(
    items: List<ProfileImports.Item>,
    known: Set<String>,
    valid: (String) -> Boolean,
): RestorePlan {
    val unique = items.groupBy { it.source }.values.map { same -> same.first().copy(active = same.any { it.active }) }
    val (good, rejected) = unique.partition { valid(it.source) }
    val load = good.filterNot { it.source in known }

    return RestorePlan(load, good.size - load.size, rejected)
}
