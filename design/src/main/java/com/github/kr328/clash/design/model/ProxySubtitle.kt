package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.Proxy

// Подпись под именем узла: описание сервера от провайдера, иначе — что дало
// ядро (протокол с транспортом, «VLESS RAW (TCP) · Reality», или подпись по
// ui-subtitle-pattern). Без туннеля ядро узлов не знает, и подпись берётся из
// подписки (protocol) — только у таких строк: в ядре узлы провайдеров бывают
// одноимёнными, и подпись по имени могла бы лечь не на тот сервер.
//
// hideBadges — подписка скрыла у серверов протокол, транспорт и защиту
// (clod-hide-badges): у сервера остаётся только описание и подпись по
// ui-subtitle-pattern — её ядро вырезает из имени, и заголовок строки тогда
// короче имени. У групп подпись — их тип, она остаётся.
fun proxySubtitle(proxy: Proxy, description: String?, protocol: String?, hideBadges: Boolean = false): String {
    description?.takeIf { it.isNotBlank() }?.let { return it }

    if (hideBadges && !proxy.isGroup) {
        return proxy.subtitle.takeIf { proxy.title != proxy.name.trim() }.orEmpty()
    }

    return protocol?.takeIf { it.isNotBlank() && proxy.type.isEmpty() && proxy.subtitle.isBlank() }
        ?: proxy.subtitle
}
