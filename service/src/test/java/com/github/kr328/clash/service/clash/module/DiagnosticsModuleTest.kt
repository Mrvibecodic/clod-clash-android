package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.DiagnosticsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsModuleTest {
    @Test
    fun diagnosticsStatusUsesVerifiedReadiness() {
        val connecting = DiagnosticsStatus(available = true, running = true, ready = false, failed = false)
        val ready = connecting.copy(ready = true)
        val failed = connecting.copy(failed = true)

        assertEquals(DiagnosticsState.CONNECTING, diagnosticsState(enabled = true, connecting))
        assertEquals(DiagnosticsState.RUNNING, diagnosticsState(enabled = true, ready))
        assertEquals(DiagnosticsState.ERROR, diagnosticsState(enabled = true, failed))
        assertEquals(DiagnosticsState.STOPPED, diagnosticsState(enabled = false, failed))
    }
}
