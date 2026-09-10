package com.github.kr328.clash.service

import android.content.Intent
import android.os.Binder
import android.os.SystemClock
import android.os.IBinder
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.ClashRuntime
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class ClashService : BaseService() {
    private val self: ClashService
        get() = this

    private val session = SessionLifecycle(this, this) { runtime.launch() }

    private val runtime: ClashRuntime = clashRuntime {
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

                                    session.notifyReady()
                                }

                                false
                            }
                            is ConfigurationModule.Event.LoadFailed -> {
                                session.reason = it.message

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

            session.reason = e.message
        } finally {
            withContext(NonCancellable) {
                session.beginStop()

                session.finishSession()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        ServiceLog.mark("ClashService: create, running = ${StatusProvider.serviceRunning}")

        session.claimUserIntent()

        if (StatusProvider.serviceRunning) {
            return session.rejectStart()
        }

        StaticNotificationModule.createNotificationChannel(this)

        session.startSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceLog.mark(
            "ClashService: start command $startId, restarted by system = ${intent == null}, " +
                "rejected = ${session.rejected}, stopped = ${session.stopped}"
        )

        return when (session.onStartCommand(intent == null, startId)) {
            StartCommandOutcome.Rejected,
            StartCommandOutcome.StopSticky,
            StartCommandOutcome.StopStartFailed -> START_NOT_STICKY
            else -> START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }

    override fun onDestroy() {
        ServiceLog.mark("ClashService: destroy, rejected = ${session.rejected}")

        if (session.rejected) {
            super.onDestroy()

            return
        }

        val startedAt = SystemClock.elapsedRealtime()

        session.destroy()

        cancelAndJoinBlocking()

        Log.i(
            "ClashService destroyed in ${SystemClock.elapsedRealtime() - startedAt} ms: " +
                (session.reason ?: "successfully")
        )

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }
}
