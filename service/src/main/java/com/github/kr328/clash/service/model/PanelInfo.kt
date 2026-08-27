package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

@Serializable
data class PanelInfo(
    val title: String = "",
    val logoFile: String = "",
    val announce: String = "",
    val announceUrl: String = "",
    val supportUrl: String = "",
    val portalUrl: String = "",
    val botUrl: String = "",
    val monitorUrl: String = "",
    val guideUrl: String = "",
    val promo: String = "",
    val promoUrl: String = "",
    val hwidState: String = "",

    val hwidLimitMessage: String = "",

    val hwidMaxDevices: Int = 0,

    val refillDate: Long = 0,

    val notifyExpireDays: List<Int>? = null,
    val notifyTrafficPercent: List<Int>? = null,

    val clockSkew: Long = 0,
    val clockSkewAt: Long = 0,

    val migrateUrl: String = "",

    val lockMode: Boolean? = null,

    val noServers: Boolean = false,

    val sentinels: List<String> = emptyList(),

    val descriptions: Map<String, String> = emptyMap(),

    val disablePing: Boolean = false,

    val groups: List<PanelGroup> = emptyList(),
) {
    fun clockSkewMillis(): Long {
        if (clockSkew == 0L || clockSkewAt == 0L) return 0

        val age = System.currentTimeMillis() / 1000 - clockSkewAt

        return if (age in 0..MAX_CLOCK_SKEW_AGE_SECONDS) clockSkew * 1000 else 0
    }

    val isEmpty: Boolean
        get() = title.isBlank() && announce.isBlank() && promo.isBlank() &&
            portalUrl.isBlank() && logoFile.isBlank() && groups.isEmpty()
}

private const val MAX_CLOCK_SKEW_AGE_SECONDS = 30L * 24 * 60 * 60

@Serializable
data class PanelGroup(
    val name: String = "",
    val type: String = "",
    val proxies: List<String> = emptyList(),
)
