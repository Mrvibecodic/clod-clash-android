package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.DiagnosticsRuntimeState
import com.github.kr328.clash.core.DiagnosticsStatus
import com.github.kr328.clash.core.model.DiagnosticsAccess
import com.github.kr328.clash.core.model.ExternalControllerAccess
import com.github.kr328.clash.service.model.DiagnosticsSessionAccess
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint
import com.github.kr328.clash.service.util.sendDiagnosticsStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private enum class DiagnosticsStatusSource {
    NATIVE,
    CONFIGURATION_ERROR,
}

internal data class DiagnosticsSession(
    val endpoint: String,
    val access: DiagnosticsAccess,
    val controller: ExternalControllerAccess,
)

internal fun resolveDiagnosticsSession(
    endpoint: String,
    credential: DiagnosticsCredential?,
): DiagnosticsSession? {
    val normalizedEndpoint = normalizeDiagnosticsEndpoint(endpoint) ?: return null
    val sessionAccess = DiagnosticsSessionAccess.from(credential)
    val diagnosticsAccess = sessionAccess.diagnostics ?: return null
    return DiagnosticsSession(normalizedEndpoint, diagnosticsAccess, sessionAccess.controller)
}

internal fun parseDiagnosticsMode(value: String?): DiagnosticsMode {
    return DiagnosticsMode.entries.firstOrNull { it.name == value } ?: DiagnosticsMode.DISABLED
}

internal fun diagnosticsState(mode: DiagnosticsMode, status: DiagnosticsStatus): DiagnosticsState {
    if (mode == DiagnosticsMode.DISABLED) return DiagnosticsState.STOPPED

    return when (status.state) {
        DiagnosticsRuntimeState.CONNECTING -> DiagnosticsState.CONNECTING
        DiagnosticsRuntimeState.READY -> DiagnosticsState.RUNNING
        DiagnosticsRuntimeState.CONFIGURATION_ERROR -> DiagnosticsState.CONFIGURATION_ERROR
        DiagnosticsRuntimeState.ACCESS_DENIED -> DiagnosticsState.ACCESS_DENIED
        DiagnosticsRuntimeState.UNREACHABLE -> DiagnosticsState.UNREACHABLE
    }
}

/** Runs only while the already-started Clash service owns the core. */
class DiagnosticsModule(
    service: Service,
) : Module<Unit>(service) {
    private val store = ServiceStore(service)
    private val credentials = DiagnosticsCredentialStore(service)
    private var mode = DiagnosticsMode.DISABLED
    private var statusSource = DiagnosticsStatusSource.NATIVE

    override suspend fun run() {
        val changes = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_DIAGNOSTICS_CHANGED)
        }

        try {
            Clash.stopDiagnostics()
            while (currentCoroutineContext().isActive) {
                changes.tryReceive().getOrNull()?.let { intent ->
                    mode = parseDiagnosticsMode(intent.getStringExtra(Intents.EXTRA_DIAGNOSTICS_MODE))
                    applySetting()
                }
                publishStatus()
                delay(1_000)
            }
        } finally {
            withContext(NonCancellable) {
                mode = DiagnosticsMode.DISABLED
                statusSource = DiagnosticsStatusSource.NATIVE
                Clash.stopDiagnostics()
                useLocalControllerAccess()
                service.sendDiagnosticsStatus(DiagnosticsState.STOPPED)
            }
        }
    }

    private fun applySetting() {
        if (mode == DiagnosticsMode.DISABLED) {
            statusSource = DiagnosticsStatusSource.NATIVE
            Clash.stopDiagnostics()
            useLocalControllerAccess()
            Log.i("[Diagnostics] stopped")
            return
        }

        val session = resolveDiagnosticsSession(store.diagnosticsEndpoint, credentials.read())
        if (session == null) {
            statusSource = DiagnosticsStatusSource.CONFIGURATION_ERROR
            Clash.stopDiagnostics()
            useLocalControllerAccess()
            Log.w("[Diagnostics] configuration_error: access_unavailable")
            return
        }

        runCatching {
            Clash.stopDiagnostics()
            Clash.configureExternalController(session.controller)
            Clash.startDiagnostics(session.endpoint, session.access)
        }.onSuccess {
            statusSource = DiagnosticsStatusSource.NATIVE
            Log.i("[Diagnostics] start_requested")
        }.onFailure {
            statusSource = DiagnosticsStatusSource.CONFIGURATION_ERROR
            Clash.stopDiagnostics()
            useLocalControllerAccess()
            Log.w("[Diagnostics] configuration_error: controller_setup_failed")
        }
    }

    private fun useLocalControllerAccess() {
        runCatching { Clash.configureExternalController(ExternalControllerAccess.LocalOnly) }
            .onFailure { Log.w("[Diagnostics] local_controller_rotation_failed") }
    }

    private fun publishStatus() {
        val status = when (statusSource) {
            DiagnosticsStatusSource.NATIVE -> Clash.queryDiagnostics()
            DiagnosticsStatusSource.CONFIGURATION_ERROR ->
                DiagnosticsStatus(DiagnosticsRuntimeState.CONFIGURATION_ERROR)
        }
        service.sendDiagnosticsStatus(diagnosticsState(mode, status))
    }
}
