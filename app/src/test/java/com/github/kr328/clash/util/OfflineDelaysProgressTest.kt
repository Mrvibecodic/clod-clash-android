package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDelaysProgressTest {
    @Test
    fun `measured and total come out of the packed progress`() {
        assertEquals(120 to 500, OfflineDelays.progressOf((120L shl 32) or 500L))
    }

    @Test
    fun `no test running reads as zero of zero`() {
        assertEquals(0 to 0, OfflineDelays.progressOf(0L))
    }

    @Test
    fun `large subscriptions keep both numbers`() {
        assertEquals(70_000 to 70_000, OfflineDelays.progressOf((70_000L shl 32) or 70_000L))
    }
}
