package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.model.DiagnosticsLogEvent
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.DiagnosticsRuntimeState
import com.github.kr328.clash.core.DiagnosticsStatus
import com.github.kr328.clash.core.model.DiagnosticsAccess
import com.github.kr328.clash.core.model.ExternalControllerAccess
import com.github.kr328.clash.service.model.DiagnosticsSessionAccess
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.store.DiagnosticsCredentialReadStatus
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint
import com.github.kr328.clash.service.util.DiagnosticsEventJournal
import com.github.kr328.clash.service.util.DiagnosticsModeCommandStore
import com.github.kr328.clash.service.util.sendDiagnosticsStatus
import kotlinx.coroutines.NonCancellable
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
    private val pendingEvents = DiagnosticsEventJournal(service)
    private val pendingModes = DiagnosticsModeCommandStore(service)
    private val processedModeCommands = linkedSetOf<String>()
    private var mode = DiagnosticsMode.DISABLED
    private var statusSource = DiagnosticsStatusSource.NATIVE
    private var publishedState: DiagnosticsState? = null

    override suspend fun run() {
        val changes = receiveBroadcast {
            addAction(Intents.ACTION_DIAGNOSTICS_CHANGED)
            addAction(Intents.ACTION_DIAGNOSTICS_LOG_EVENT)
        }

        try {
            Clash.stopDiagnostics()
            record(DiagnosticsLogEvent.ServiceModuleStarted)
            flushPendingEvents()
            while (currentCoroutineContext().isActive) {
                applyPendingModes()
                while (true) {
                    val intent = changes.tryReceive().getOrNull() ?: break
                    val eventCode = intent.getIntExtra(Intents.EXTRA_DIAGNOSTICS_LOG_EVENT, -1)
                    if (eventCode >= 0) record(DiagnosticsLogEvent.fromCode(eventCode))
                    if (intent.action == Intents.ACTION_DIAGNOSTICS_LOG_EVENT) continue
                    val commandId = intent.getStringExtra(Intents.EXTRA_DIAGNOSTICS_MODE_COMMAND_ID)
                    if (commandId != null && !rememberModeCommand(commandId)) continue
                    if (commandId != null && !pendingModes.acknowledge(commandId)) {
                        record(DiagnosticsLogEvent.ServiceModeCommandAckFailed)
                    }
                    applyMode(parseDiagnosticsMode(intent.getStringExtra(Intents.EXTRA_DIAGNOSTICS_MODE)))
                }
                flushPendingEvents()
                publishStatus()
                delay(1_000)
            }
        } finally {
            withContext(NonCancellable) {
                mode = DiagnosticsMode.DISABLED
                statusSource = DiagnosticsStatusSource.NATIVE
                Clash.stopDiagnostics()
                useLocalControllerAccess()
                record(DiagnosticsLogEvent.ServiceCleanupCompleted)
                service.sendDiagnosticsStatus(DiagnosticsState.STOPPED)
            }
        }
    }

    private fun applyPendingModes() {
        pendingModes.drain().forEach { command ->
            rememberModeCommand(command.key)
            command.event?.let(::record)
            record(DiagnosticsLogEvent.ServicePendingModeApplied)
            applyMode(command.mode)
        }
    }

    private fun applyMode(newMode: DiagnosticsMode) {
        mode = newMode
        record(
            if (mode == DiagnosticsMode.ENABLED) DiagnosticsLogEvent.ServiceModeEnabled
            else DiagnosticsLogEvent.ServiceModeDisabled,
        )
        applySetting()
    }

    private fun rememberModeCommand(commandId: String): Boolean {
        if (!processedModeCommands.add(commandId)) return false
        while (processedModeCommands.size > MAX_PROCESSED_MODE_COMMANDS) {
            processedModeCommands.remove(processedModeCommands.first())
        }
        return true
    }

    private fun applySetting() {
        if (mode == DiagnosticsMode.DISABLED) {
            statusSource = DiagnosticsStatusSource.NATIVE
            Clash.stopDiagnostics()
            useLocalControllerAccess()
            return
        }

        if (normalizeDiagnosticsEndpoint(store.diagnosticsEndpoint) == null) {
            rejectSession(DiagnosticsLogEvent.ServiceSessionRejectedEndpointInvalid)
            return
        }
        val credentialRead = credentials.readResult()
        when (credentialRead.status) {
            DiagnosticsCredentialReadStatus.Missing -> {
                rejectSession(DiagnosticsLogEvent.ServiceSessionRejectedCredentialMissing)
                return
            }
            DiagnosticsCredentialReadStatus.InvalidDiscarded -> {
                rejectSession(DiagnosticsLogEvent.ServiceSessionRejectedCredentialInvalid)
                return
            }
            DiagnosticsCredentialReadStatus.Success -> Unit
        }
        val session = resolveDiagnosticsSession(store.diagnosticsEndpoint, credentialRead.credential)
        if (session == null) {
            rejectSession(DiagnosticsLogEvent.ServiceSessionRejectedCredentialInvalid)
            return
        }
        record(DiagnosticsLogEvent.ServiceSessionResolved)

        runCatching {
            Clash.stopDiagnostics()
            Clash.configureExternalController(session.controller)
            record(DiagnosticsLogEvent.ServiceControllerDiagnosticsApplied)
            record(DiagnosticsLogEvent.ServiceTunnelStartRequested)
            Clash.startDiagnostics(session.endpoint, session.access)
        }.onSuccess {
            statusSource = DiagnosticsStatusSource.NATIVE
        }.onFailure {
            statusSource = DiagnosticsStatusSource.CONFIGURATION_ERROR
            Clash.stopDiagnostics()
            useLocalControllerAccess()
            record(DiagnosticsLogEvent.ServiceControllerApplyFailed)
        }
    }

    private fun rejectSession(event: DiagnosticsLogEvent) {
        statusSource = DiagnosticsStatusSource.CONFIGURATION_ERROR
        Clash.stopDiagnostics()
        useLocalControllerAccess()
        record(event)
    }

    private fun useLocalControllerAccess() {
        runCatching { Clash.configureExternalController(ExternalControllerAccess.LocalOnly) }
            .onSuccess { record(DiagnosticsLogEvent.ServiceControllerLocalApplied) }
            .onFailure { record(DiagnosticsLogEvent.ServiceControllerApplyFailed) }
    }

    private fun flushPendingEvents() {
        val events = pendingEvents.drain()
        events.forEach(::record)
        if (events.isNotEmpty()) record(DiagnosticsLogEvent.ServicePendingEventsFlushed)
    }

    private fun publishStatus() {
        val status = when (statusSource) {
            DiagnosticsStatusSource.NATIVE -> Clash.queryDiagnostics()
            DiagnosticsStatusSource.CONFIGURATION_ERROR ->
                DiagnosticsStatus(DiagnosticsRuntimeState.CONFIGURATION_ERROR)
        }
        val state = diagnosticsState(mode, status)
        if (state != publishedState) {
            publishedState = state
            when (state) {
                DiagnosticsState.STOPPED -> record(DiagnosticsLogEvent.StateStopped)
                DiagnosticsState.CONNECTING -> record(DiagnosticsLogEvent.StateConnecting)
                DiagnosticsState.RUNNING -> record(DiagnosticsLogEvent.StateReady)
                DiagnosticsState.CONFIGURATION_ERROR -> record(DiagnosticsLogEvent.StateConfigurationError)
                DiagnosticsState.ACCESS_DENIED -> record(DiagnosticsLogEvent.StateAccessDenied)
                DiagnosticsState.UNREACHABLE -> record(DiagnosticsLogEvent.StateUnreachable)
            }
        }
        service.sendDiagnosticsStatus(state)
    }

    private fun record(event: DiagnosticsLogEvent) = Clash.recordDiagnosticsEvent(event)

    private companion object {
        const val MAX_PROCESSED_MODE_COMMANDS = 64
    }
}
