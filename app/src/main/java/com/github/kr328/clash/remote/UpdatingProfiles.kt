package com.github.kr328.clash.remote

import java.util.UUID

sealed interface UpdatingProfiles {
    data class Known(val uuids: Set<UUID>) : UpdatingProfiles

    data object Unavailable : UpdatingProfiles
}
