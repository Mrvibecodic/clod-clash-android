package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.ProfileMode
import com.github.kr328.clash.core.model.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePickTest {
    @Test
    fun unknownModeSelectsRule() {
        assertEquals(TunnelState.Mode.Rule, effectiveMode(ProfileMode()))
    }

    @Test
    fun actingModeIsSelected() {
        for (source in ProfileMode.Source.entries) {
            assertEquals(
                TunnelState.Mode.Global,
                effectiveMode(ProfileMode(TunnelState.Mode.Global, source)),
            )
        }
    }

    @Test
    fun pickingTheActingModeSavesNothing() {
        assertFalse(shouldSaveModePick(ProfileMode(), TunnelState.Mode.Rule))
        assertFalse(
            shouldSaveModePick(
                ProfileMode(TunnelState.Mode.Direct, ProfileMode.Source.Template),
                TunnelState.Mode.Direct,
            ),
        )
        assertFalse(
            shouldSaveModePick(
                ProfileMode(TunnelState.Mode.Global, ProfileMode.Source.Choice),
                TunnelState.Mode.Global,
            ),
        )
    }

    @Test
    fun pickingAnotherModeSavesIt() {
        assertTrue(shouldSaveModePick(ProfileMode(), TunnelState.Mode.Global))
        assertTrue(
            shouldSaveModePick(
                ProfileMode(TunnelState.Mode.Rule, ProfileMode.Source.Template),
                TunnelState.Mode.Direct,
            ),
        )
        assertTrue(
            shouldSaveModePick(
                ProfileMode(TunnelState.Mode.Global, ProfileMode.Source.Override),
                TunnelState.Mode.Rule,
            ),
        )
    }
}
