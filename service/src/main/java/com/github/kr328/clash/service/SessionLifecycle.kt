package com.github.kr328.clash.service

import android.app.Service
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.github.kr328.clash.service.clash.module.StaticNotificationModule
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class StartCommandOutcome {
    Rejected,
    StopSticky,
    StopStartFailed,
    StartSession,
    Remember,
    Ignore,
}

enum class AfterStopOutcome {
    Done,
    StartSession,
    Abandon,
}

fun startCommandOutcome(
    rejected: Boolean,
    stopping: Boolean,
    systemStart: Boolean,
    stickyAllowed: Boolean,
    startFailed: Boolean,
    stopped: Boolean,
): StartCommandOutcome = when {
    rejected -> StartCommandOutcome.Rejected
    stopping -> StartCommandOutcome.Remember
    systemStart && !stickyAllowed -> StartCommandOutcome.StopSticky
    startFailed -> StartCommandOutcome.StopStartFailed
    stopped -> StartCommandOutcome.StartSession
    else -> StartCommandOutcome.Ignore
}

fun shouldWarnAlwaysOnBusy(running: Boolean, ready: Boolean, alwaysOn: Boolean): Boolean =
    running && !ready && alwaysOn

fun afterStopOutcome(
    stopSelfSucceeded: Boolean,
    restartRequested: Boolean,
    stickyAllowed: Boolean,
): AfterStopOutcome = when {
    stopSelfSucceeded && !restartRequested -> AfterStopOutcome.Done
    !stickyAllowed -> AfterStopOutcome.Abandon
    else -> AfterStopOutcome.StartSession
}

class SessionLifecycle(
    private val service: Service,
    private val scope: CoroutineScope,
    private val launchRuntime: () -> Unit,
) {
    @Volatile
    var reason: String? = null

    var systemStarted = false

    var rejected = false
        private set

    private var sessionStartedAt: Long = 0

    private var startFailed = false

    private var wantedByUser = false

    private val stopNotified = AtomicBoolean(false)

    private val stopping = AtomicBoolean(false)

    private val restartRequested = AtomicBoolean(false)

    private val restartBySystem = AtomicBoolean(false)

    @Volatile
    private var destroyed = false

    @Volatile
    private var lastStartId = -1

    @Volatile
    private var stopId = -1

    val stopped: Boolean
        get() = stopNotified.get()

    fun notifyStopped() {
        if (!stopNotified.compareAndSet(false, true))
            return

        StatusProvider.serviceReady = false
        StatusProvider.startupStage = null
        StatusProvider.currentProfileUuid = null
        StatusProvider.serviceRunning = false

        service.sendClashStopped(reason)

        reason?.let {
            if (systemStarted) {
                StaticNotificationModule.notifyStartFailed(service, it)
            }
        }
    }

    fun notifyReady() {
        StatusProvider.startupStage = null
        StatusProvider.serviceReady = true

        ServiceStore(service).stickyRestarts = ""

        StaticNotificationModule.cancelStartFailed(service)

        service.sendClashStarted()
    }

    fun claimUserIntent() {
        wantedByUser = StatusProvider.shouldStartClashOnBoot
    }

    fun rejectStart() {
        rejected = true

        StaticNotificationModule.createNotificationChannel(service)

        if (StaticNotificationModule.notifyRejectedNotification(service)) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }

        service.stopSelf()
    }

    private fun stickyRestartAllowed(): Boolean {
        if (!wantedByUser) {
            ServiceLog.mark("sticky restart refused: stopped by user")

            return false
        }

        val store = ServiceStore(service)
        val count = store.recordStickyRestart(SystemClock.elapsedRealtime(), STICKY_RESTART_WINDOW_MS)

        if (count >= STICKY_RESTART_LIMIT) {
            store.stickyRestarts = ""

            reason = service.getString(R.string.clod_crash_loop, count)

            ServiceLog.mark("sticky restart refused: $count restarts in window")

            return false
        }

        return true
    }

    fun startSession() {
        stopNotified.set(false)

        reason = null

        StatusProvider.serviceReady = false
        StatusProvider.serviceRunning = true

        sessionStartedAt = ServiceStore(service).markSessionStarted()

        if (!StaticNotificationModule.notifyLoadingNotification(service)) {
            startFailed = true

            reason = service.getString(R.string.clod_foreground_denied)

            notifyStopped()

            service.stopSelf()

            return
        }

        launchRuntime()
    }

    fun onStartCommand(systemStart: Boolean, startId: Int): StartCommandOutcome {
        val pending = !rejected && stopping.get()

        if (!pending) {
            lastStartId = startId
        }

        if (!rejected && !pending && systemStart) {
            systemStarted = true
        }

        val outcome = startCommandOutcome(
            rejected = rejected,
            stopping = pending,
            systemStart = systemStart,
            stickyAllowed = if (!rejected && !pending && systemStart) stickyRestartAllowed() else true,
            startFailed = startFailed,
            stopped = stopNotified.get(),
        )

        when (outcome) {
            StartCommandOutcome.Rejected -> service.stopSelf()
            StartCommandOutcome.Remember -> {
                restartRequested.set(true)

                if (systemStart) {
                    restartBySystem.set(true)
                }

                ServiceLog.mark("start command $startId held until stop finishes")
            }
            StartCommandOutcome.StopSticky -> {
                notifyStopped()

                service.stopSelf()
            }
            StartCommandOutcome.StopStartFailed -> {
                reason = service.getString(R.string.clod_foreground_denied)

                notifyStopped()

                service.stopSelf()
            }
            StartCommandOutcome.StartSession -> startSession()
            StartCommandOutcome.Ignore -> Unit
        }

        return outcome
    }

    fun beginStop() {
        stopId = lastStartId

        stopping.set(true)
    }

    fun finishSession() {
        notifyStopped()

        val stopSelfSucceeded = service.stopSelfResult(stopId)
        val requested = restartRequested.getAndSet(false)
        val bySystem = restartBySystem.getAndSet(false)

        stopping.set(false)

        if (requested && bySystem) {
            systemStarted = true
        }

        val outcome = afterStopOutcome(
            stopSelfSucceeded = stopSelfSucceeded,
            restartRequested = requested,
            stickyAllowed = if (requested && bySystem) stickyRestartAllowed() else true,
        )

        ServiceLog.mark("stop finished, self stopped = $stopSelfSucceeded, next = $outcome")

        when (outcome) {
            AfterStopOutcome.Done -> Unit
            AfterStopOutcome.Abandon -> service.stopSelf()
            AfterStopOutcome.StartSession -> scope.launch(Dispatchers.Main) {
                if (destroyed || !stopNotified.get()) return@launch

                startSession()
            }
        }
    }

    fun destroy() {
        destroyed = true

        notifyStopped()

        ServiceStore(service).clearSessionStarted(sessionStartedAt)
    }

    private companion object {
        private const val STICKY_RESTART_WINDOW_MS = 10 * 60 * 1000L
        private const val STICKY_RESTART_LIMIT = 3
    }
}
