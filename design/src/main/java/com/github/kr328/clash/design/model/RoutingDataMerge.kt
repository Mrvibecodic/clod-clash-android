package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.compose.screen.ProviderFileState

object RoutingDataMerge {
    fun merge(fresh: List<ProviderFileState>, known: List<ProviderFileState>): List<ProviderFileState> {
        val previous = known.associateBy { it.key }

        return fresh.map {
            val old = previous[it.key] ?: return@map it

            it.copy(error = old.error)
        }
    }
}
