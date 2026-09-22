package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object TunnelState {
    @Serializable
    enum class Mode {
        @SerialName("direct")
        Direct,

        @SerialName("global")
        Global,

        @SerialName("rule")
        Rule,

        @SerialName("script")
        Script,
    }
}
