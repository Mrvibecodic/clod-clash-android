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

    val refillDate: Long = 0,

    val notifyExpireDays: List<Int>? = null,
    val notifyTrafficPercent: List<Int>? = null,

    val clockSkew: Long = 0,
    val clockSkewAt: Long = 0,

    val moveUrl: String = "",

    // Отпечаток ключа прослойки последней загрузки по защищённому каналу.
    val chanKey: String = "",

    val noServers: Boolean = false,

    val sentinels: List<String> = emptyList(),

    val descriptions: Map<String, String> = emptyMap(),

    // Подпись протокола с транспортом у узлов из proxies подписки — для списка
    // без туннеля: «VLESS RAW (TCP) · Reality»
    val protocols: Map<String, String> = emptyMap(),

    // Серверы только для мобильной сети (clod-mobile-only): вне сети SIM их нет
    val mobileOnly: List<String> = emptyList(),

    val disablePing: Boolean = false,

    // clod:freeze — панель включила проверку 16–20 заголовком clod-16-20-check: true
    val freezeCheck: Boolean = false,

    // Панель скрыла у серверов подпись протокола, транспорта и защиты
    // заголовком clod-hide-badges: true
    val hideBadges: Boolean = false,

    val pingFast: Int = 0,
    val pingMedium: Int = 0,

    val groups: List<PanelGroup> = emptyList(),

    val main: String? = null,
) {
    fun clockSkewMillis(): Long {
        if (clockSkew == 0L || clockSkewAt == 0L) return 0

        val age = System.currentTimeMillis() / 1000 - clockSkewAt

        return if (age in 0..MAX_CLOCK_SKEW_AGE_SECONDS) clockSkew * 1000 else 0
    }

    fun hides(name: String): Boolean = name in sentinels

    // Сервер только для мобильной сети, а сеть не мобильная: его не видно
    fun hidesOffMobile(name: String, cellular: Boolean): Boolean = !cellular && name in mobileOnly

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
