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
        assertEquals(ToggleIntent.Stop, toggleIntent(ConnectionStatus.Connecting))
    }

    @Test
    fun connectedStops() {
        assertEquals(ToggleIntent.Stop, toggleIntent(ConnectionStatus.Connected))
    }

    @Test
    fun disconnectingIgnores() {
        assertEquals(ToggleIntent.Ignore, toggleIntent(ConnectionStatus.Disconnecting))
    }

    @Test
    fun disconnectedStarts() {
        assertEquals(ToggleIntent.Start, toggleIntent(ConnectionStatus.Disconnected))
    }

    @Test
    fun disconnectingIsDisabledAndSaysDisconnecting() {
        val intent = toggleIntent(ConnectionStatus.Disconnecting)

        assertFalse(intent.enabled)
        assertEquals(R.string.clod_status_disconnecting, intent.label())
    }

    @Test
    fun connectingSaysDisconnect() {
        val intent = toggleIntent(ConnectionStatus.Connecting)

        assertTrue(intent.enabled)
        assertEquals(R.string.clod_action_disconnect, intent.label())
    }

    @Test
    fun disconnectedSaysConnect() {
        val intent = toggleIntent(ConnectionStatus.Disconnected)

        assertTrue(intent.enabled)
        assertEquals(R.string.clod_action_connect, intent.label())
    }
}
