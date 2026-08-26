package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.DiagnosticsRuntimeState
import com.github.kr328.clash.core.DiagnosticsStatus
import com.github.kr328.clash.core.model.ExternalControllerAccess
import com.github.kr328.clash.service.store.DiagnosticsCredential
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsModuleTest {
    @Test
    fun diagnosticsSessionUsesTheCredentialReadForEachEnable() {
        val first = requireNotNull(
            DiagnosticsCredential.create("first", "first-password", "first-controller", 19091),
        )
        val second = requireNotNull(
            DiagnosticsCredential.create("second", "second-password", "second-controller", 19092),
        )

        val firstSession = requireNotNull(resolveDiagnosticsSession("https://example.com", first))
        val secondSession = requireNotNull(resolveDiagnosticsSession("https://example.com", second))

        assertEquals("first:first-password", firstSession.access.tunnelAuth)
        assertEquals("second:second-password", secondSession.access.tunnelAuth)
        assertEquals(19092, secondSession.access.remotePort)
        assertEquals(
            "second-controller",
            (secondSession.controller as ExternalControllerAccess.Diagnostics).secret,
        )
    }

    @Test
    fun diagnosticsSessionRejectsMissingCredential() {
        assertEquals(null, resolveDiagnosticsSession("https://example.com", null))
    }

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
