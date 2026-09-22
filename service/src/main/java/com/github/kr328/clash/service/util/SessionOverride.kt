package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.TunnelState

fun sessionOverrideFor(choice: TunnelState.Mode?): ConfigurationOverride =
    ConfigurationOverride(mode = choice)
