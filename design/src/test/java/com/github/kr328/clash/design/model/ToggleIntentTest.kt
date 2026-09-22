package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToggleIntentTest {
    @Test
    fun connectingStops() {
        assertEquals(ToggleIntent.Stop, toggleIntent(ConnectionStatus.Connecting, false))
        assertEquals(ToggleIntent.Stop, toggleIntent(ConnectionStatus.Connecting, true))
    }

    @Test
    fun connectedStops() {
        assertEquals(ToggleIntent.Stop, toggleIntent(ConnectionStatus.Connected, false))
        assertEquals(ToggleIntent.Stop, toggleIntent(ConnectionStatus.Connected, true))
    }

    @Test
    fun disconnectingIgnores() {
        assertEquals(ToggleIntent.Ignore, toggleIntent(ConnectionStatus.Disconnecting, false))
        assertEquals(ToggleIntent.Ignore, toggleIntent(ConnectionStatus.Disconnecting, true))
    }

    @Test
    fun disconnectedStarts() {
        assertEquals(ToggleIntent.Start, toggleIntent(ConnectionStatus.Disconnected, false))
    }

    @Test
    fun disconnectedWithLiveServiceStops() {
        assertEquals(ToggleIntent.Stop, toggleIntent(ConnectionStatus.Disconnected, true))
    }

    @Test
    fun disconnectingIsDisabledAndSaysDisconnecting() {
        val intent = toggleIntent(ConnectionStatus.Disconnecting, true)

        assertFalse(intent.enabled)
        assertEquals(R.string.clod_status_disconnecting, intent.label())
    }

    @Test
    fun disconnectedWithLiveServiceSaysDisconnect() {
        val intent = toggleIntent(ConnectionStatus.Disconnected, true)

        assertTrue(intent.enabled)
        assertEquals(R.string.clod_action_disconnect, intent.label())
    }

    @Test
    fun connectingSaysDisconnect() {
        val intent = toggleIntent(ConnectionStatus.Connecting, false)

        assertTrue(intent.enabled)
        assertEquals(R.string.clod_action_disconnect, intent.label())
    }

    @Test
    fun disconnectedSaysConnect() {
        val intent = toggleIntent(ConnectionStatus.Disconnected, false)

        assertTrue(intent.enabled)
        assertEquals(R.string.clod_action_connect, intent.label())
    }
}
