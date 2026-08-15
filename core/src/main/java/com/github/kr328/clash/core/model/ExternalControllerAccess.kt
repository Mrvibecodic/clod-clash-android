package com.github.kr328.clash.core.model

sealed interface ExternalControllerAccess {
    data object LocalOnly : ExternalControllerAccess

    class Diagnostics(val secret: String) : ExternalControllerAccess {
        init {
            require(secret.isNotBlank()) { "controller secret must not be blank" }
        }
    }
}
