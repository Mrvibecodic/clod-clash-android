package com.github.kr328.clash.util

import java.util.UUID

object HealthProbes {
    @Volatile
    var checkedGroups: List<String> = emptyList()

    @Volatile
    var checkedAt: Long = 0

    @Volatile
    var offlineProfile: UUID? = null

    @Volatile
    var offlineDelays: Map<String, Int> = emptyMap()
}
