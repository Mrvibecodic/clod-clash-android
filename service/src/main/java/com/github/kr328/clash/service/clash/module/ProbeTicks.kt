package com.github.kr328.clash.service.clash.module

internal const val IDLE_TICKS_PER_PROBE = 3

// Бодрствуем: экран включён или человек велел держать туннель — пробы без пропусков
internal fun awake(interactive: Boolean, keepAwake: Boolean): Boolean = interactive || keepAwake

internal fun ticksPerProbe(awake: Boolean): Int = if (awake) 1 else IDLE_TICKS_PER_PROBE
