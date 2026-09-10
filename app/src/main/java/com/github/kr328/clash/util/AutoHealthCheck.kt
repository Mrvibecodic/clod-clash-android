package com.github.kr328.clash.util

internal fun shouldAutoHealthCheck(
    clashRunning: Boolean,
    startedByReload: Boolean,
    groupsKnown: Boolean,
    readOnly: Boolean,
    sinceLastCheckMs: Long,
    staleMs: Long,
): Boolean = clashRunning && !startedByReload && groupsKnown && !readOnly && sinceLastCheckMs > staleMs
