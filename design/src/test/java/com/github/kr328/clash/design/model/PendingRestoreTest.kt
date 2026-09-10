package com.github.kr328.clash.design.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingRestoreTest {
    @Test
    fun pendingValueWins() {
        assertEquals(PendingRestore.UsePending, pendingRestore(flagSet = true, valuePresent = true))
    }

    @Test
    fun lostPendingValueWarns() {
        assertEquals(PendingRestore.UseStoredAndWarn, pendingRestore(flagSet = true, valuePresent = false))
    }

    @Test
    fun noFlagUsesStored() {
        assertEquals(PendingRestore.UseStored, pendingRestore(flagSet = false, valuePresent = false))
    }

    @Test
    fun valueWithoutFlagIsStillUsed() {
        assertEquals(PendingRestore.UsePending, pendingRestore(flagSet = false, valuePresent = true))
    }
}
