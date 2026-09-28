package com.github.kr328.clash.service.model

import com.github.kr328.clash.core.model.DiagnosticsAccess
import com.github.kr328.clash.core.model.ExternalControllerAccess
import com.github.kr328.clash.core.DiagnosticsBootstrap
import com.github.kr328.clash.service.store.DiagnosticsCredential

internal class DiagnosticsSessionAccess private constructor(
    val controller: ExternalControllerAccess,
    val diagnostics: DiagnosticsAccess?,
) {
    companion object {
        fun from(credential: DiagnosticsCredential?, bootstrap: DiagnosticsBootstrap?): DiagnosticsSessionAccess {
            if (credential == null || bootstrap == null || bootstrap.controllerSecret.isBlank() ||
                bootstrap.remotePort !in DiagnosticsAccess.MIN_REMOTE_PORT..DiagnosticsAccess.MAX_REMOTE_PORT
            ) {
                return DiagnosticsSessionAccess(ExternalControllerAccess.LocalOnly, null)
            }
            return DiagnosticsSessionAccess(
                ExternalControllerAccess.Diagnostics(bootstrap.controllerSecret),
                DiagnosticsAccess(
                    credential.chiselAuth,
                    bootstrap.controllerSecret,
                    bootstrap.remotePort,
                ),
            )
        }
    }
}
