package com.github.kr328.clash.design.model

private const val GLOBAL_GROUP = "GLOBAL"

private val BLOCKING_SELECTIONS = setOf("REJECT", "REJECT-DROP")

fun globalRoutingBlocked(groupNames: List<String>, now: String?): Boolean {
    if (groupNames.singleOrNull() != GLOBAL_GROUP) return false

    return now in BLOCKING_SELECTIONS
}
