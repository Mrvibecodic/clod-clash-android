package com.github.kr328.clash.service.clash.module

internal const val IDLE_TICKS_PER_PROBE = 3

internal fun ticksPerProbe(interactive: Boolean, keepAwake: Boolean): Int =
    if (interactive || keepAwake) 1 else IDLE_TICKS_PER_PROBE
