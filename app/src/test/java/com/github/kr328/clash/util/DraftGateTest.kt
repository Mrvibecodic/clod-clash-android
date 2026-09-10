package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftGateTest {
    @Test
    fun `a draft is saved only when it is a valid unsaved change of a screen nobody is committing`() {
        for (canceled in listOf(false, true)) {
            for (changed in listOf(false, true)) {
                for (valid in listOf(false, true)) {
                    for (committing in listOf(false, true)) {
                        assertEquals(
                            "canceled=$canceled changed=$changed valid=$valid committing=$committing",
                            !canceled && changed && valid && !committing,
                            DraftGate.savesOnStop(canceled, changed, valid, committing),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a screen whose subscription is loading is not closed by a background restart`() {
        assertEquals(false, DraftGate.closesOnServiceRecreated(committing = true))
    }

    @Test
    fun `a screen with nothing in flight is closed by a background restart`() {
        assertEquals(true, DraftGate.closesOnServiceRecreated(committing = false))
    }
}
