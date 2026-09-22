package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.ProfileMode
import com.github.kr328.clash.core.model.TunnelState

fun effectiveMode(mode: ProfileMode): TunnelState.Mode = mode.mode ?: TunnelState.Mode.Rule

fun shouldSaveModePick(mode: ProfileMode, picked: TunnelState.Mode): Boolean =
    picked != effectiveMode(mode)
