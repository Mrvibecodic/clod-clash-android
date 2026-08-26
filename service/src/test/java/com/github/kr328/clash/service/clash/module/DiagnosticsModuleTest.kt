package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.common.model.DiagnosticsLogEvent
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.DiagnosticsRuntimeState
import com.github.kr328.clash.core.DiagnosticsStatus
import com.github.kr328.clash.core.model.ExternalControllerAccess
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.util.decodeDiagnosticsEvents
import com.github.kr328.clash.service.util.decodeDiagnosticsModeCommands
import com.github.kr328.clash.service.util.retainedDiagnosticsEvents
import com.github.kr328.clash.service.util.retainedDiagnosticsModeCommands
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsModuleTest {
    @Test
    fun diagnosticsLogEventCodesAndNamesAreUnique() {
        assertEquals(DiagnosticsLogEvent.entries.size, DiagnosticsLogEvent.entries.map { it.code }.toSet().size)
        assertEquals(DiagnosticsLogEvent.entries.size, DiagnosticsLogEvent.entries.map { it.wireName }.toSet().size)
    }

    @Test
    fun diagnosticsEventJournalKeepsTheLatestThirtyTwoCodes() {
        val encoded = (0 until 40).associate { index ->
            "diagnostics_pending_log_event_$index" to "$index,${DiagnosticsLogEvent.SettingsSaveRequested.code}"
        }

        val events = decodeDiagnosticsEvents(encoded)
        assertEquals(40, events.size)
        assertEquals(32, retainedDiagnosticsEvents(encoded).size)
        assertEquals(
            setOf(DiagnosticsLogEvent.SettingsSaveRequested),
            retainedDiagnosticsEvents(encoded).map { it.event }.toSet(),
        )
    }

    @Test
    fun diagnosticsModeJournalKeepsOrderedCommandsAndEvents() {
        val encoded = (0 until 20).associate { index ->
            val mode = if (index % 2 == 0) DiagnosticsMode.ENABLED else DiagnosticsMode.DISABLED
            "diagnostics_pending_mode_$index" to "$index,${mode.name},${DiagnosticsLogEvent.UiEnableRequested.code}"
        }

        val commands = decodeDiagnosticsModeCommands(encoded)
        assertEquals(20, commands.size)
        assertEquals(16, retainedDiagnosticsModeCommands(encoded).size)
        assertEquals(DiagnosticsMode.ENABLED, commands.first().mode)
        assertEquals(DiagnosticsMode.DISABLED, commands.last().mode)
        assertEquals(DiagnosticsLogEvent.UiEnableRequested, commands.last().event)
    }

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
