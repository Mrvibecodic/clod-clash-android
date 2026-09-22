package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ActiveProfileTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun goneClearsOnlyItself() {
        assertEquals(ActiveProfileAction.Clear, activeProfileGone(a, a))
        assertEquals(ActiveProfileAction.Keep, activeProfileGone(a, b))
        assertEquals(ActiveProfileAction.Keep, activeProfileGone(null, a))
    }

    @Test
    fun rollbackRestoresExistingRetained() {
        assertEquals(ActiveProfileAction.Restore, activeProfileRollback(b, b, a, true))
    }

    @Test
    fun rollbackSkipsMissingRetained() {
        assertEquals(ActiveProfileAction.Keep, activeProfileRollback(b, b, a, false))
    }

    @Test
    fun rollbackSkipsWhenRetainedIsFailed() {
        assertEquals(ActiveProfileAction.Keep, activeProfileRollback(a, a, a, true))
    }

    @Test
    fun rollbackSkipsWhenStoredMoved() {
        assertEquals(ActiveProfileAction.Keep, activeProfileRollback(a, b, a, true))
        assertEquals(ActiveProfileAction.Keep, activeProfileRollback(null, b, a, true))
    }

    @Test
    fun rollbackSkipsWithoutRetained() {
        assertEquals(ActiveProfileAction.Keep, activeProfileRollback(b, b, null, true))
    }

    @Test
    fun selectSetsOnlyWhenPointerMoves() {
        assertEquals(ActiveProfileAction.Keep, activeProfileSelect(a, a, true))
        assertEquals(ActiveProfileAction.Set, activeProfileSelect(b, a, true))
        assertEquals(ActiveProfileAction.Set, activeProfileSelect(null, a, true))
    }

    @Test
    fun selectSkipsMissingProfile() {
        assertEquals(ActiveProfileAction.Keep, activeProfileSelect(null, a, false))
        assertEquals(ActiveProfileAction.Keep, activeProfileSelect(b, a, false))
    }
}
