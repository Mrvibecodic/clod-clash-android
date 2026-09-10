package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.compose.component.ConnectionStatus

enum class ToggleIntent {
    Start,
    Stop,
    Ignore,
}

fun toggleIntent(status: ConnectionStatus, running: Boolean): ToggleIntent = when (status) {
    ConnectionStatus.Connecting -> ToggleIntent.Stop
    ConnectionStatus.Connected -> ToggleIntent.Stop
    ConnectionStatus.Disconnecting -> ToggleIntent.Ignore
    ConnectionStatus.Disconnected -> if (running) ToggleIntent.Stop else ToggleIntent.Start
}
