package com.github.kr328.clash.design.model

fun groupIndexOf(names: List<String>, name: String?, fallback: Int): Int {
    val found = names.indexOf(name)

    return if (found >= 0) found else fallback.coerceIn(0, maxOf(names.size - 1, 0))
}
