package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.AccessControlMode

// Какие приложения идут через туннель: пакет → UID на момент расчёта. Android
// применяет состав только при establish() и сопоставляет его приложениям по UID,
// а у переустановленного приложения UID новый. Поэтому служба помнит применённый
// состав и сверяет его с нужным после загрузки подписки и после установки или
// удаления приложений. uidOf — null для неустановленного пакета.
data class TunApps(val allowed: Map<String, Int>, val disallowed: Map<String, Int>)

fun tunApps(
    mode: AccessControlMode,
    selected: Set<String>,
    include: Set<String>,
    exclude: Set<String>,
    self: String,
    uidOf: (String) -> Int?,
): TunApps {
    fun Set<String>.installed(): Map<String, Int> =
        mapNotNull { name -> uidOf(name)?.let { name to it } }.toMap()

    val includes = include - exclude

    return when (mode) {
        AccessControlMode.AcceptAll -> if (includes.installed().isNotEmpty()) {
            TunApps((includes + self).installed(), emptyMap())
        } else {
            TunApps(emptyMap(), (exclude - self).installed())
        }
        AccessControlMode.AcceptSelected ->
            TunApps((selected + includes + self).installed(), emptyMap())
        AccessControlMode.DenySelected ->
            TunApps(emptyMap(), (selected + exclude - self).installed())
    }
}

// Пакеты, удалённые после применения, из сравнения выпадают: их трафика больше
// нет, и само удаление пересборки не требует. Пересборку вызывают только
// расхождения среди установленных сейчас — в том числе новый UID у
// переустановленного приложения.
fun tunAppsChanged(applied: TunApps, wanted: TunApps, uidOf: (String) -> Int?): Boolean =
    applied.allowed.filterKeys { uidOf(it) != null } != wanted.allowed ||
        applied.disallowed.filterKeys { uidOf(it) != null } != wanted.disallowed
