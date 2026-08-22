package com.github.kr328.clash.service.model

import com.github.kr328.clash.core.model.DiagnosticsAccess
import com.github.kr328.clash.core.model.ExternalControllerAccess
import com.github.kr328.clash.service.store.DiagnosticsCredential

internal class DiagnosticsSessionAccess private constructor(
    val controller: ExternalControllerAccess,
    val diagnostics: DiagnosticsAccess?,
) {
    companion object {
        fun from(credential: DiagnosticsCredential?): DiagnosticsSessionAccess {
            if (credential == null) {
                return DiagnosticsSessionAccess(ExternalControllerAccess.LocalOnly, null)
            }
            return DiagnosticsSessionAccess(
                ExternalControllerAccess.Diagnostics(credential.controllerSecret),
                DiagnosticsAccess(
                    credential.chiselAuth,
                    credential.controllerSecret,
                    credential.remotePort,
                ),
            )
        }
    }
}
