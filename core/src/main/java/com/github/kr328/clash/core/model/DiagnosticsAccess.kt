package com.github.kr328.clash.core.model

class DiagnosticsAccess(
    val tunnelAuth: String,
    val controllerSecret: String,
    val remotePort: Int,
) {
    init {
        require(tunnelAuth.isNotBlank()) { "diagnostics tunnel auth must not be blank" }
        require(controllerSecret.isNotBlank()) { "controller secret must not be blank" }
        require(remotePort in MIN_REMOTE_PORT..MAX_REMOTE_PORT) { "diagnostics remote port is invalid" }
    }

    companion object {
        const val MIN_REMOTE_PORT = 1024
        const val MAX_REMOTE_PORT = 65535
    }
}
