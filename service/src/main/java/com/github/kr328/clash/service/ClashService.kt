package com.github.kr328.clash.service

import android.content.Intent
import android.os.Binder
import android.os.SystemClock
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ClashService : BaseService() {
    private val self: ClashService
        get() = this

    private var reason: String? = null

    private var sessionStartedAt: Long = 0

    private var rejected = false

    private val stopNotified = AtomicBoolean(false)

    private var systemStarted = false

    private var startFailed = false

    private var wantedByUser = false

    @Volatile
    private var lastStartId = -1

    private fun notifyStopped() {
        if (!stopNotified.compareAndSet(false, true))
            return

        StatusProvider.serviceReady = false
        StatusProvider.startupStage = null
        StatusProvider.serviceRunning = false

        sendClashStopped(reason)

        reason?.let {
            if (systemStarted) {
                StaticNotificationModule.notifyStartFailed(this, it)
            }
        }
    }

    private fun notifyReady() {
        StatusProvider.startupStage = null
        StatusProvider.serviceReady = true

        ServiceStore(this).stickyRestarts = ""

        StaticNotificationModule.cancelStartFailed(this)

        sendClashStarted()
    }

    companion object {
        private const val STICKY_RESTART_WINDOW_MS = 10 * 60 * 1000L
        private const val STICKY_RESTART_LIMIT = 3
    }

    private val runtime = clashRuntime {
        val store = ServiceStore(self)

        val close = install(CloseModule(self))
        val config = install(ConfigurationModule(self))
        val network = install(NetworkObserveModule(self))

        if (store.dynamicNotification)
            install(DynamicNotificationModule(self))
        else
            install(StaticNotificationModule(self))

        install(AppListCacheModule(self))
        install(TimeZoneModule(self))
        install(SuspendModule(self))

        var ready = false

        try {
            while (isActive) {
                val quit = select<Boolean> {
                    close.onEvent {
                        true
                    }
                    config.onEvent {
                        when (it) {
                            is ConfigurationModule.Event.Loaded -> {
                                if (!ready) {
                                    ready = true

                                    notifyReady()
                                }

                                false
                            }
                            is ConfigurationModule.Event.LoadFailed -> {
                                reason = it.message

                                true
                            }
                        }
                    }
                    network.onEvent {
                        false
                    }
                }

                if (quit) break
            }
        } catch (e: Exception) {
            Log.e("Create clash runtime: ${e.message}", e)

            reason = e.message
        } finally {
            withContext(NonCancellable) {
                notifyStopped()

                stopSelfResult(lastStartId)
            }
        }
    }

    private fun rejectStart() {
        rejected = true

        StaticNotificationModule.createNotificationChannel(this)

        if (StaticNotificationModule.notifyRejectedNotification(this)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }

        stopSelf()
    }

    private fun stickyRestartAllowed(): Boolean {
        if (!wantedByUser) {
            ServiceLog.mark("sticky restart refused: stopped by user")

            return false
        }

        val store = ServiceStore(this)
        val count = store.recordStickyRestart(SystemClock.elapsedRealtime(), STICKY_RESTART_WINDOW_MS)

        if (count >= STICKY_RESTART_LIMIT) {
            store.stickyRestarts = ""

            reason = getString(R.string.clod_crash_loop, count)

            ServiceLog.mark("sticky restart refused: $count restarts in window")

            return false
        }

        return true
    }

    override fun onCreate() {
        super.onCreate()

        ServiceLog.mark("ClashService: create, running = ${StatusProvider.serviceRunning}")

        wantedByUser = StatusProvider.shouldStartClashOnBoot

        if (StatusProvider.serviceRunning) {
            return rejectStart()
        }

        StaticNotificationModule.createNotificationChannel(this)

        startSession()
    }

    private fun startSession() {
        stopNotified.set(false)

        reason = null

        StatusProvider.serviceReady = false
        StatusProvider.serviceRunning = true

        sessionStartedAt = ServiceStore(this).markSessionStarted()

        if (!StaticNotificationModule.notifyLoadingNotification(this)) {
            startFailed = true

            return
        }

        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceLog.mark(
            "ClashService: start command $startId, restarted by system = ${intent == null}, " +
                "rejected = $rejected, stopped = ${stopNotified.get()}"
        )

        lastStartId = startId

        if (rejected) {
            stopSelf()

            return START_NOT_STICKY
        }

        if (intent == null) {
            systemStarted = true

            if (!stickyRestartAllowed()) {
                notifyStopped()

                stopSelf()

                return START_NOT_STICKY
            }
        }

        if (startFailed) {
            reason = getString(R.string.clod_foreground_denied)

            notifyStopped()

            stopSelf()

            return START_NOT_STICKY
        }

        if (stopNotified.get()) {
            startSession()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }

    override fun onDestroy() {
        ServiceLog.mark("ClashService: destroy, rejected = $rejected")

        if (rejected) {
            super.onDestroy()

            return
        }

        val startedAt = SystemClock.elapsedRealtime()

        notifyStopped()

        ServiceStore(this).clearSessionStarted(sessionStartedAt)

        cancelAndJoinBlocking()

        Log.i(
            "ClashService destroyed in ${SystemClock.elapsedRealtime() - startedAt} ms: " +
                (reason ?: "successfully")
        )

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }
}
