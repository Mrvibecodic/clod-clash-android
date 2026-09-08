package com.github.kr328.clash.design.util

private const val FIRST_STRONG_ISOLATE = '\u2068'

private const val POP_DIRECTIONAL_ISOLATE = '\u2069'

fun String.bidiIsolated(): String =
    if (isEmpty()) this else "$FIRST_STRONG_ISOLATE$this$POP_DIRECTIONAL_ISOLATE"
