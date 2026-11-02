package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ProviderValues

enum class ProviderReset { Reload, Mode, Stack }

fun providerResets(keys: List<String>): Set<ProviderReset> {
    val core = keys - ProviderValues.MODE_CHOICE - ProviderValues.TUN_STACK

    if (core.any { it != ProviderValues.MODE }) return setOf(ProviderReset.Reload)

    return buildSet {
        if (ProviderValues.MODE_CHOICE in keys || ProviderValues.MODE in keys) add(ProviderReset.Mode)
        if (ProviderValues.TUN_STACK in keys) add(ProviderReset.Stack)
    }
}
