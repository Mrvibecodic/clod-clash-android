package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.DiagnosticsRuntimeState
import com.github.kr328.clash.core.DiagnosticsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsModuleTest {
    @Test
    fun diagnosticsModeParsingFailsClosed() {
        assertEquals(DiagnosticsMode.ENABLED, parseDiagnosticsMode(DiagnosticsMode.ENABLED.name))
        assertEquals(DiagnosticsMode.DISABLED, parseDiagnosticsMode("unknown"))
        assertEquals(DiagnosticsMode.DISABLED, parseDiagnosticsMode(null))
    }

    @Test
    fun diagnosticsStatusUsesVerifiedReadiness() {
        val connecting = DiagnosticsStatus(DiagnosticsRuntimeState.CONNECTING)
        val ready = DiagnosticsStatus(DiagnosticsRuntimeState.READY)
        val configurationError = DiagnosticsStatus(DiagnosticsRuntimeState.CONFIGURATION_ERROR)
        val accessDenied = DiagnosticsStatus(DiagnosticsRuntimeState.ACCESS_DENIED)
        val unreachable = DiagnosticsStatus(DiagnosticsRuntimeState.UNREACHABLE)

        assertEquals(DiagnosticsState.CONNECTING, diagnosticsState(DiagnosticsMode.ENABLED, connecting))
        assertEquals(DiagnosticsState.RUNNING, diagnosticsState(DiagnosticsMode.ENABLED, ready))
        assertEquals(DiagnosticsState.CONFIGURATION_ERROR, diagnosticsState(DiagnosticsMode.ENABLED, configurationError))
        assertEquals(DiagnosticsState.ACCESS_DENIED, diagnosticsState(DiagnosticsMode.ENABLED, accessDenied))
        assertEquals(DiagnosticsState.UNREACHABLE, diagnosticsState(DiagnosticsMode.ENABLED, unreachable))
        assertEquals(DiagnosticsState.STOPPED, diagnosticsState(DiagnosticsMode.DISABLED, unreachable))
    }
}
