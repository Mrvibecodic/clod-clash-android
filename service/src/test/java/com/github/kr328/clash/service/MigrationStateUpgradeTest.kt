package com.github.kr328.clash.service

import com.github.kr328.clash.service.ProfileProcessor.MigrationState
import com.github.kr328.clash.service.ProfileProcessor.MigrationVisit
import com.github.kr328.clash.service.ProfileProcessor.upgradeMigrationState
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationStateUpgradeTest {
    private val now = 1_000L

    @Test
    fun emptyStateKeepsZeroHops() {
        val upgraded = upgradeMigrationState(MigrationState(), now)

        assertEquals(0, upgraded.hops)
        assertEquals(emptyList<MigrationVisit>(), upgraded.history)
        assertEquals(emptyList<String>(), upgraded.previous)
    }

    @Test
    fun oldHopsKeepCounterAndStayWithoutTimestamp() {
        val upgraded = upgradeMigrationState(MigrationState(hops = 2), now)

        assertEquals(2, upgraded.hops)
        assertEquals(0L, upgraded.lastAt)
        assertEquals(emptyList<MigrationVisit>(), upgraded.history)
    }

    @Test
    fun oldPreviousBecomesHistory() {
        val upgraded = upgradeMigrationState(
            MigrationState(hops = 2, previous = listOf("a", "b")),
            now,
        )

        assertEquals(2, upgraded.hops)
        assertEquals(0L, upgraded.lastAt)
        assertEquals(listOf(MigrationVisit("a", now), MigrationVisit("b", now)), upgraded.history)
        assertEquals(emptyList<String>(), upgraded.previous)
    }

    @Test
    fun oldPreviousWithoutHopsStillBecomesHistory() {
        val upgraded = upgradeMigrationState(MigrationState(previous = listOf("a")), now)

        assertEquals(0, upgraded.hops)
        assertEquals(0L, upgraded.lastAt)
        assertEquals(listOf(MigrationVisit("a", now)), upgraded.history)
    }

    @Test
    fun newFormatIsUntouched() {
        val state = MigrationState(hops = 1, lastAt = 500, history = listOf(MigrationVisit("a", 400)))

        assertEquals(state, upgradeMigrationState(state, now))
    }

    @Test
    fun newFormatWithEmptyHistoryIsUntouched() {
        val state = MigrationState(hops = 1, lastAt = 500)

        assertEquals(state, upgradeMigrationState(state, now))
    }

    @Test
    fun longPreviousIsTrimmedToLastTen() {
        val previous = (1..20).map { "url$it" }

        val upgraded = upgradeMigrationState(MigrationState(previous = previous), now)

        assertEquals(10, upgraded.history.size)
        assertEquals("url11", upgraded.history.first().url)
        assertEquals("url20", upgraded.history.last().url)
    }

    @Test
    fun upgradeIsIdempotent() {
        val once = upgradeMigrationState(MigrationState(hops = 3, previous = listOf("a")), now)
        val twice = upgradeMigrationState(once, 2_000L)

        assertEquals(once, twice)
    }

    @Test
    fun historyWinsOverPrevious() {
        val upgraded = upgradeMigrationState(
            MigrationState(
                hops = 1,
                history = listOf(MigrationVisit("kept", 400)),
                previous = listOf("dropped"),
            ),
            now,
        )

        assertEquals(listOf(MigrationVisit("kept", 400)), upgraded.history)
        assertEquals(emptyList<String>(), upgraded.previous)
    }
}
