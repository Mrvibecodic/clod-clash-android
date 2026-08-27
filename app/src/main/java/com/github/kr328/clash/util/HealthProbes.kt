package com.github.kr328.clash.util

import java.util.UUID

object HealthProbes {
    var checkedGroups: List<String> = emptyList()

    var checkedAt: Long = 0

    var offlineProfile: UUID? = null

    var offlineDelays: Map<String, Int> = emptyMap()
}
