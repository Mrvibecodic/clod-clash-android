package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

@Serializable
data class InboundPrefs(
    val mixedPort: Int = 0,
    val httpPort: Int = 0,
) {
    val localProxyPort: Int
        get() = if (mixedPort != 0) mixedPort else httpPort
}
