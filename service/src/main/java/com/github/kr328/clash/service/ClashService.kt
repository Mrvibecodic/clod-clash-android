package com.github.kr328.clash.service

import android.content.Intent
import android.os.Binder
import android.os.SystemClock
import android.os.IBinder
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

    private fun notifyStopped() {
        if (!stopNotified.compareAndSet(false, true))
            return

        StatusProvider.serviceRunning = false

        sendClashStopped(reason)
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

        try {
            while (isActive) {
                val quit = select<Boolean> {
                    close.onEvent {
                        true
                    }
                    config.onEvent {
                        reason = it.message

                        true
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

                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (StatusProvider.serviceRunning) {
            rejected = true

            return stopSelf()
        }

        StatusProvider.serviceRunning = true

        sessionStartedAt = ServiceStore(this).markSessionStarted()

        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)

        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rejected) {
            stopSelf()

            return START_NOT_STICKY
        }

        if (stopNotified.get()) {
            stopSelf()

            sendClashStopped(null)

            return START_NOT_STICKY
        }

        sendClashStarted()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }

    override fun onDestroy() {
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
