package com.github.kr328.clash.util

internal fun shouldAutoHealthCheck(
    startedByReload: Boolean,
    groupsKnown: Boolean,
    readOnly: Boolean,
    sinceLastCheckMs: Long,
    staleMs: Long,
): Boolean = !startedByReload && groupsKnown && !readOnly && sinceLastCheckMs > staleMs
