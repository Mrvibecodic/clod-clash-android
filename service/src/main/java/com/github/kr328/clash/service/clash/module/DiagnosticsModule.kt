package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.DiagnosticsRuntimeState
import com.github.kr328.clash.core.DiagnosticsStatus
import com.github.kr328.clash.core.model.DiagnosticsAccess
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
    private val access: DiagnosticsAccess?,
) : Module<Unit>(service) {
    private val store = ServiceStore(service)
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
                service.sendDiagnosticsStatus(DiagnosticsState.STOPPED)
            }
        }
    }

    private fun applySetting() {
        val endpoint = normalizeDiagnosticsEndpoint(store.diagnosticsEndpoint)
        if (mode == DiagnosticsMode.DISABLED) {
            statusSource = DiagnosticsStatusSource.NATIVE
            Clash.stopDiagnostics()
            return
        }
        if (endpoint == null || access == null) {
            statusSource = DiagnosticsStatusSource.CONFIGURATION_ERROR
            Clash.stopDiagnostics()
            return
        }

        statusSource = DiagnosticsStatusSource.NATIVE
        Clash.startDiagnostics(endpoint, access)
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
