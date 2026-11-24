package com.github.kr328.clash.service.clash.module

internal fun shouldProbe(now: Long, lastProbeAt: Long, minGapMs: Long, force: Boolean): Boolean =
    force || now - lastProbeAt >= minGapMs
