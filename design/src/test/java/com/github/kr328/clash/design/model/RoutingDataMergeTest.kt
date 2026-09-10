package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.compose.screen.ProviderFileState
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingDataMergeTest {
    private fun provider(
        key: String,
        updating: Boolean = false,
        error: String? = null,
        updatedAt: Long = 0,
    ) = ProviderFileState(key = key, name = key, updatedAt = updatedAt, updating = updating, error = error)

    @Test
    fun `an unknown provider is taken as it comes`() {
        val fresh = listOf(provider("rules/alpha", updating = true))

        assertEquals(fresh, RoutingDataMerge.merge(fresh, emptyList()))
    }

    @Test
    fun `the fresh updating flag always wins`() {
        val merged = RoutingDataMerge.merge(
            listOf(provider("rules/alpha", updating = false)),
            listOf(provider("rules/alpha", updating = true)),
        )

        assertEquals(false, merged.single().updating)
    }

    @Test
    fun `a fresh updating flag is not overwritten by a stale idle one`() {
        val merged = RoutingDataMerge.merge(
            listOf(provider("rules/alpha", updating = true)),
            listOf(provider("rules/alpha", updating = false)),
        )

        assertEquals(true, merged.single().updating)
    }

    @Test
    fun `an error survives a reload because it has no other source`() {
        val merged = RoutingDataMerge.merge(
            listOf(provider("rules/alpha")),
            listOf(provider("rules/alpha", error = "boom")),
        )

        assertEquals("boom", merged.single().error)
    }

    @Test
    fun `a provider that disappeared is gone`() {
        val merged = RoutingDataMerge.merge(
            listOf(provider("rules/beta")),
            listOf(provider("rules/alpha", error = "boom")),
        )

        assertEquals(listOf("rules/beta"), merged.map { it.key })
        assertEquals(null, merged.single().error)
    }

    @Test
    fun `fresh timestamps are kept`() {
        val merged = RoutingDataMerge.merge(
            listOf(provider("rules/alpha", updatedAt = 42)),
            listOf(provider("rules/alpha", updatedAt = 1)),
        )

        assertEquals(42L, merged.single().updatedAt)
    }
}
