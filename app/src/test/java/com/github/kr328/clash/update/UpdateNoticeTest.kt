package com.github.kr328.clash.update

import com.github.kr328.clash.update.UpdateNotice.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateNoticeTest {
    @Test
    fun aNewVersionIsAnnounced() {
        assertEquals(Plan(withdraw = false, post = 12400), UpdateNotice.plan(target = 12400, shown = null, announced = setOf(12308)))
    }

    @Test
    fun anAnnouncedVersionIsNotAnnouncedAgainAfterItsNoticeIsGone() {
        assertEquals(Plan(withdraw = false, post = null), UpdateNotice.plan(target = 12400, shown = null, announced = setOf(12400)))
    }

    @Test
    fun aShownNoticeAboutTheTargetStays() {
        assertEquals(Plan(withdraw = false, post = null), UpdateNotice.plan(target = 12400, shown = 12400, announced = setOf(12400)))
    }

    @Test
    fun aNewerVersionReplacesTheShownNotice() {
        assertEquals(Plan(withdraw = true, post = 12401), UpdateNotice.plan(target = 12401, shown = 12400, announced = setOf(12400)))
    }

    @Test
    fun nothingToOfferWithdrawsTheNotice() {
        assertEquals(Plan(withdraw = true, post = null), UpdateNotice.plan(target = null, shown = 12400, announced = setOf(12400)))
        assertEquals(Plan(withdraw = false, post = null), UpdateNotice.plan(target = null, shown = null, announced = emptySet()))
    }

    @Test
    fun switchingChannelsMovesTheNoticeButNeverRepeatsAVersion() {
        assertEquals(Plan(withdraw = true, post = 12400), UpdateNotice.plan(target = 12400, shown = 12409, announced = setOf(12409)))
        assertEquals(Plan(withdraw = true, post = null), UpdateNotice.plan(target = 12400, shown = 12409, announced = setOf(12400, 12409)))
    }

    @Test
    fun theAnnouncedListKeepsTheLatestVersionsWithoutDuplicates() {
        assertEquals(listOf(1L, 2L, 3L), UpdateNotice.announce(listOf(1, 3, 2), 3))
        assertEquals((3L..10L).toList(), UpdateNotice.announce((1L..9L).toList(), 10))
    }
}
