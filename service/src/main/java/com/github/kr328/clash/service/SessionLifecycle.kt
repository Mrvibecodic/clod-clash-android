package com.github.kr328.clash.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.service.clash.module.CloseModule
import com.github.kr328.clash.service.clash.module.StaticNotificationModule
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    restartRequested: Boolean,
    stickyAllowed: Boolean,
): AfterStopOutcome = when {
    !restartRequested -> AfterStopOutcome.Done
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

    @Volatile
    var unattendedStart = false

    @Volatile
    var systemProxyRefused = false

    @Volatile
    private var restartedBySystem = false

    var rejected = false
        private set

    private var sessionStartedAt: Long = 0

    private var startFailed = false

    private var wantedByUser = false

    private val stopNotified = AtomicBoolean(false)

    private val stopping = AtomicBoolean(false)

    private val pendingStart = AtomicReference<PendingStart?>(null)

    @Volatile
    private var destroyed = false

    @Volatile
    private var lastStartId = -1

    @Volatile
    private var stopId = -1

    private var stopRequestHolder: BroadcastReceiver? = null

    val stopped: Boolean
        get() = stopNotified.get()

    fun notifyStopped() {
        if (!stopNotified.compareAndSet(false, true))
            return

        StatusProvider.serviceReady = false
        StatusProvider.startupStage = null
        StatusProvider.currentProfileUuid = null
        StatusProvider.restartedBySystem = false
        StatusProvider.systemProxyRefused = false
        StatusProvider.serviceRunning = false

        service.sendClashStopped(reason)

        reason?.let {
            if (systemStarted || unattendedStart) {
                StaticNotificationModule.notifyStartFailed(service, it)
            }
        }
    }

    fun notifyReady() {
        StatusProvider.startupStage = null
        StatusProvider.restartedBySystem = restartedBySystem
        StatusProvider.systemProxyRefused = systemProxyRefused
        StatusProvider.serviceReady = true

        ServiceStore(service).stickyRestarts = ""

        unattendedStart = false

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
        holdStopRequests()

        stopNotified.set(false)

        reason = null

        restartedBySystem = false
        systemProxyRefused = false

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

    fun onStartCommand(systemStart: Boolean, unattended: Boolean, startId: Int): StartCommandOutcome {
        val pending = !rejected && stopping.get()

        if (!pending) {
            lastStartId = startId

            pendingStart.set(null)
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

        if (systemStart && outcome == StartCommandOutcome.Ignore) {
            restartedBySystem = true
        }

        when (outcome) {
            StartCommandOutcome.Rejected -> service.stopSelf()
            StartCommandOutcome.Remember -> {
                val held = pendingStart.get()

                pendingStart.set(
                    PendingStart(
                        startId = startId,
                        bySystem = systemStart && (held?.bySystem ?: true),
                        unattended = unattended || held?.unattended == true,
                    ),
                )

                ServiceLog.mark("start command $startId held until stop finishes")
            }
            StartCommandOutcome.StopSticky -> {
                notifyStopped()

                service.stopSelf()
            }
            StartCommandOutcome.StopStartFailed -> {
                val failure = service.getString(R.string.clod_foreground_denied)

                reason = failure

                service.sendClashStopped(failure)

                if (unattended && !systemStarted) {
                    StaticNotificationModule.notifyStartFailed(service, failure)
                }

                service.stopSelf()
            }
            StartCommandOutcome.StartSession -> {
                unattendedStart = unattended

                startSession()
            }
            StartCommandOutcome.Ignore -> if (unattended && !StatusProvider.serviceReady) {
                unattendedStart = true
            }
        }

        return outcome
    }

    fun beginStop() {
        stopId = lastStartId

        stopping.set(true)
    }

    fun finishSession() {
        notifyStopped()

        val held = pendingStart.getAndSet(null)

        if (held != null) {
            lastStartId = held.startId
        }

        val stopSelfSucceeded = service.stopSelfResult(stopId)

        stopping.set(false)

        if (held != null && held.bySystem) {
            systemStarted = true
        }

        if (held != null) {
            unattendedStart = held.unattended
        }

        val outcome = afterStopOutcome(
            restartRequested = held != null,
            stickyAllowed = if (held != null && held.bySystem) stickyRestartAllowed() else true,
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

    private fun holdStopRequests() {
        releaseStopRequests()

        CloseModule.forgetRequests()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                CloseModule.rememberRequest()
            }
        }

        service.registerReceiverCompat(
            receiver,
            IntentFilter(Intents.ACTION_CLASH_REQUEST_STOP),
            Permissions.RECEIVE_SELF_BROADCASTS,
            null,
        )

        stopRequestHolder = receiver
    }

    private fun releaseStopRequests() {
        stopRequestHolder?.let { receiver ->
            runCatching { service.unregisterReceiver(receiver) }
        }

        stopRequestHolder = null
    }

    fun destroy() {
        destroyed = true

        releaseStopRequests()

        notifyStopped()

        ServiceStore(service).clearSessionStarted(sessionStartedAt)
    }

    private data class PendingStart(val startId: Int, val bySystem: Boolean, val unattended: Boolean)

    private companion object {
        private const val STICKY_RESTART_WINDOW_MS = 10 * 60 * 1000L
        private const val STICKY_RESTART_LIMIT = 3
    }
}
