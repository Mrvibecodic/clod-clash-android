package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.Clash
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

internal fun diagnosticsState(enabled: Boolean, status: DiagnosticsStatus) = when {
    !enabled -> DiagnosticsState.STOPPED
    status.failed -> DiagnosticsState.ERROR
    status.ready -> DiagnosticsState.RUNNING
    else -> DiagnosticsState.CONNECTING
}

/** Runs only while the already-started Clash service owns the core. */
class DiagnosticsModule(
    service: Service,
    private val access: DiagnosticsAccess?,
) : Module<Unit>(service) {
    private val store = ServiceStore(service)
    private var enabled = false

    override suspend fun run() {
        val changes = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_DIAGNOSTICS_CHANGED)
        }

        try {
            Clash.stopDiagnostics()
            while (currentCoroutineContext().isActive) {
                changes.tryReceive().getOrNull()?.let { intent ->
                    enabled = intent.getBooleanExtra(Intents.EXTRA_DIAGNOSTICS_ENABLED, false)
                    applySetting()
                }
                publishStatus()
                delay(1_000)
            }
        } finally {
            withContext(NonCancellable) {
                enabled = false
                Clash.stopDiagnostics()
                service.sendDiagnosticsStatus(DiagnosticsState.STOPPED)
            }
        }
    }

    private fun applySetting() {
        val status = Clash.queryDiagnostics()
        val endpoint = normalizeDiagnosticsEndpoint(store.diagnosticsEndpoint)
        if (enabled && status.available && endpoint != null && access != null) {
            Clash.startDiagnostics(endpoint, access)
        } else {
            Clash.stopDiagnostics()
        }
    }

    private fun publishStatus() {
        val status = Clash.queryDiagnostics()
        service.sendDiagnosticsStatus(diagnosticsState(enabled, status))
    }
}
