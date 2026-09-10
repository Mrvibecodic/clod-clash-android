package com.github.kr328.clash.design.util

private const val PROBE_CONCURRENCY = 10

private const val PROBE_TIMEOUT_SECONDS = 5

private const val TOTAL_TIMEOUT_SECONDS = 45

internal fun measuringBudgetSeconds(total: Int): Int {
    val need = (total / PROBE_CONCURRENCY + 2) * PROBE_TIMEOUT_SECONDS

    return if (need < TOTAL_TIMEOUT_SECONDS) TOTAL_TIMEOUT_SECONDS else need
}

internal fun measuringMinutes(total: Int): Int {
    val seconds = measuringBudgetSeconds(total)

    return (seconds + 59) / 60
}
