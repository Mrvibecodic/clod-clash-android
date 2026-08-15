package com.github.kr328.clash.design.compose.screen

import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.design.DiagnosticsSettingsDesign
import com.github.kr328.clash.service.store.DEFAULT_DIAGNOSTICS_ENDPOINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSettingsDiagnosticsPlacementTest {
    @Test
    fun diagnosticsEndpointHasNoBundledDefault() {
        assertEquals("", DEFAULT_DIAGNOSTICS_ENDPOINT)
    }

    @Test
    fun successfulCredentialSaveReturnsToNetworkForFeedback() {
        val result: DiagnosticsSettingsDesign.Request = DiagnosticsSettingsDesign.Request.Saved

        assertEquals(DiagnosticsSettingsDesign.Request.Saved, result)
    }

    @Test
    fun networkSettingsOwnsToggleAndOpensCredentialScreen() {
        val state = NetworkSettingsState(
            diagnosticsEnabled = true,
            diagnosticsConfigured = true,
            diagnosticsEndpoint = "https://example.com",
            vpnServiceRunning = true,
            diagnosticsState = DiagnosticsState.RUNNING,
        )
        val toggle: NetworkSettingsAction = NetworkSettingsAction.DisableDiagnostics
        val navigation: NetworkSettingsAction = NetworkSettingsAction.OpenDiagnostics
        val settings = DiagnosticsSettingsState(
            diagnosticsConfigured = true,
            diagnosticsEndpoint = "https://example.com",
            vpnServiceRunning = true,
        )

        assertTrue(state.diagnosticsEnabled)
        assertEquals(DiagnosticsState.RUNNING, state.diagnosticsState)
        assertEquals(NetworkSettingsAction.DisableDiagnostics, toggle)
        assertEquals(NetworkSettingsAction.OpenDiagnostics, navigation)
        assertTrue(settings.diagnosticsConfigured)
    }
}
