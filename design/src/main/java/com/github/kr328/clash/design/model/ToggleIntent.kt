package com.github.kr328.clash.design.model

import androidx.annotation.StringRes
import com.github.kr328.clash.design.R
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

@StringRes
fun ToggleIntent.label(): Int = when (this) {
    ToggleIntent.Start -> R.string.clod_action_connect
    ToggleIntent.Stop -> R.string.clod_action_disconnect
    ToggleIntent.Ignore -> R.string.clod_status_disconnecting
}

val ToggleIntent.enabled: Boolean
    get() = this != ToggleIntent.Ignore
