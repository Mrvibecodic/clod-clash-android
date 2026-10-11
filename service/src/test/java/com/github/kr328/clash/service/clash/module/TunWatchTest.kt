package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunWatchTest {
    private val me = 10_123

    @Test
    fun ownVpnIsRecognisedByOwnerFromAndroid11() {
        assertTrue(ownVpn(sdk = 30, ownerUid = me, myUid = me))
        assertTrue(ownVpn(sdk = 35, ownerUid = me, myUid = me))
    }

    // Чужой VPN (рабочий профиль, Private Space) с 11-го показан со скрытым
    // ownerUid — свой туннель он не заменяет
    @Test
    fun foreignVpnIsNotOursFromAndroid11() {
        assertFalse(ownVpn(sdk = 30, ownerUid = -1, myUid = me))
        assertFalse(ownVpn(sdk = 35, ownerUid = -1, myUid = me))
        assertFalse(ownVpn(sdk = 35, ownerUid = 10_999, myUid = me))
    }

    @Test
    fun beforeAndroid11AnyVpnCountsAsOurs() {
        assertTrue(ownVpn(sdk = 29, ownerUid = 0, myUid = me))
        assertTrue(ownVpn(sdk = 23, ownerUid = 0, myUid = me))
    }

    @Test
    fun lossBeforeTheTunnelSettledIsIgnoredAndKeepsTheBudget() {
        var spent = false

        assertEquals(TunLoss.Ignore, tunLoss(opened = false, settled = true) { spent = true; true })
        assertEquals(TunLoss.Ignore, tunLoss(opened = true, settled = false) { spent = true; true })
        assertFalse(spent)
    }

    @Test
    fun lossReopensWhileTheBudgetLastsThenGivesUp() {
        assertEquals(TunLoss.Reopen, tunLoss(opened = true, settled = true) { true })
        assertEquals(TunLoss.GiveUp, tunLoss(opened = true, settled = true) { false })
    }
}
