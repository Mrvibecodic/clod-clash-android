package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.Proxy

// Подпись под именем узла: описание сервера от провайдера, иначе — что дало
// ядро (протокол с транспортом, «VLESS RAW (TCP) · Reality», или подпись по
// ui-subtitle-pattern). Без туннеля ядро узлов не знает, и подпись берётся из
// подписки (protocol) — только у таких строк: в ядре узлы провайдеров бывают
// одноимёнными, и подпись по имени могла бы лечь не на тот сервер.
fun proxySubtitle(proxy: Proxy, description: String?, protocol: String?): String =
    description?.takeIf { it.isNotBlank() }
        ?: protocol?.takeIf { it.isNotBlank() && proxy.type.isEmpty() && proxy.subtitle.isBlank() }
        ?: proxy.subtitle
