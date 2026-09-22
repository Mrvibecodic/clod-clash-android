package com.github.kr328.clash.core.model

import com.github.kr328.clash.core.Clash
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileModeTest {
    @Test
    fun `an unknown template mode stays empty`() {
        assertEquals(
            ProfileMode(null, ProfileMode.Source.Template),
            Clash.decodeProfileMode("""{"source":"template"}"""),
        )
    }

    @Test
    fun `every source keeps its mode`() {
        assertEquals(
            ProfileMode(TunnelState.Mode.Global, ProfileMode.Source.Choice),
            Clash.decodeProfileMode("""{"mode":"global","source":"choice"}"""),
        )
        assertEquals(
            ProfileMode(TunnelState.Mode.Direct, ProfileMode.Source.Override),
            Clash.decodeProfileMode("""{"mode":"direct","source":"override"}"""),
        )
        assertEquals(
            ProfileMode(TunnelState.Mode.Rule, ProfileMode.Source.Locked),
            Clash.decodeProfileMode("""{"mode":"rule","source":"locked"}"""),
        )
    }
}
