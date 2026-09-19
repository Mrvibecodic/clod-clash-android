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
    fun `an untouched screen of a profile without a draft closes without a question`() {
        assertEquals(true, DraftGate.exitsSilently(changed = false, imported = true, draft = false))
        assertEquals(true, DraftGate.exitsSilently(changed = false, imported = false, draft = false))
    }

    @Test
    fun `a new profile that was never imported closes without a question`() {
        assertEquals(true, DraftGate.exitsSilently(changed = false, imported = false, draft = true))
    }

    @Test
    fun `a changed screen or a draft of an imported profile asks before closing`() {
        assertEquals(false, DraftGate.exitsSilently(changed = true, imported = true, draft = false))
        assertEquals(false, DraftGate.exitsSilently(changed = true, imported = false, draft = false))
        assertEquals(false, DraftGate.exitsSilently(changed = true, imported = false, draft = true))
        assertEquals(false, DraftGate.exitsSilently(changed = false, imported = true, draft = true))
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
