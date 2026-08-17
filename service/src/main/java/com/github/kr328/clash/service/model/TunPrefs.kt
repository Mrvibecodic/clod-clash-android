package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

@Serializable
data class TunPrefs(
    val stack: String = "",
    val includePackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
)
