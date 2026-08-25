package com.github.kr328.clash.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val notes: String = "",
    val platforms: Map<String, Platform> = emptyMap(),
) {
    @Serializable
    data class Platform(
        val url: String,
        val sha256: String,
    )

    fun platformFor(abis: List<String>): Platform? =
        abis.firstNotNullOfOrNull { platforms[it] } ?: platforms["universal"]
}
