package com.github.kr328.clash.util

internal fun shouldAutoHealthCheck(
    clashRunning: Boolean,
    startedByReload: Boolean,
    groupsKnown: Boolean,
    readOnly: Boolean,
    sinceLastCheckMs: Long,
    staleMs: Long,
): Boolean = clashRunning && !startedByReload && groupsKnown && !readOnly && sinceLastCheckMs > staleMs

internal enum class HealthCheckRoute { Live, Offline, Skip }

// Какой замер идёт, решает показанная панель, а не состояние туннеля: панель из
// подписки бывает и при запущенном туннеле, пока ядро не отдало группы. Её замер —
// все узлы подписки в процессе службы, минутами, поэтому только по кнопке.
internal fun healthCheckRoute(offlinePanel: Boolean, manual: Boolean): HealthCheckRoute = when {
    !offlinePanel -> HealthCheckRoute.Live
    manual -> HealthCheckRoute.Offline
    else -> HealthCheckRoute.Skip
}
