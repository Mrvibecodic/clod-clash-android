package com.github.kr328.clash.common.model

enum class DiagnosticsMode {
    DISABLED,
    ENABLED,
}

enum class DiagnosticsState {
    STOPPED,
    CONNECTING,
    RUNNING,
    CONFIGURATION_ERROR,
    ACCESS_DENIED,
    UNREACHABLE,
}
