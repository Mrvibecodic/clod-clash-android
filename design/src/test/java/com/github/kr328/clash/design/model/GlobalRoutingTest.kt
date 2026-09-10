package com.github.kr328.clash.design.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalRoutingTest {
    @Test
    fun table() {
        val cases = listOf(
            Triple(listOf("GLOBAL"), "REJECT", true),
            Triple(listOf("GLOBAL"), "REJECT-DROP", true),
            Triple(listOf("GLOBAL"), "Auto", false),
            Triple(listOf("GLOBAL"), "DIRECT", false),
            Triple(listOf("GLOBAL"), null, false),
            Triple(listOf("GLOBAL"), "", false),
            Triple(listOf("Auto", "Manual"), "REJECT", false),
            Triple(listOf("GLOBAL", "Auto"), "REJECT", false),
            Triple(emptyList(), null, false),
            Triple(emptyList(), "REJECT", false),
        )

        for ((names, now, want) in cases) {
            assertEquals("$names / $now", want, globalRoutingBlocked(names, now))
        }
    }
}
