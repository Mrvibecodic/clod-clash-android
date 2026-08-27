package com.github.kr328.clash.remote

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.util.unbindServiceSilent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Service(private val context: Application, val crashed: () -> Unit) {
    val remote = Resource<IRemoteService>()

    @Volatile
    var boundSince: Long = 0
        private set

    private val inFlight = AtomicInteger(0)

    @Volatile
    private var unbindRequested = false

    fun beginOperation() {
        inFlight.incrementAndGet()
    }

    fun endOperation() {
        if (inFlight.decrementAndGet() == 0 && unbindRequested) {
            unbindRequested = false

            unbind()
        }
    }

    fun requestUnbind() {
        if (inFlight.get() == 0) {
            return unbind()
        }

        unbindRequested = true

        Global.launch {
            delay(UNBIND_HOLD_MS)

            if (unbindRequested) {
                unbindRequested = false

                unbind()
            }
        }
    }

    private val connection = object : ServiceConnection {
        private var lastCrashed: Long = -1

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            remote.set(service.unwrap(IRemoteService::class))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote.set(null)

            if (System.currentTimeMillis() - lastCrashed < TOGGLE_CRASHED_INTERVAL) {
                unbind()

                crashed()
            }

            lastCrashed = System.currentTimeMillis()
            Log.w("RemoteService killed or crashed")
        }
    }

    fun bind() {
        try {
            unbindRequested = false

            boundSince = System.currentTimeMillis()

            if (!context.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)) {
                Log.w("RemoteService bind refused")

                unbind()

                crashed()
            }
        } catch (e: Exception) {
            unbind()

            crashed()
        }
    }

    fun unbind() {
        boundSince = 0

        context.unbindServiceSilent(connection)

        remote.set(null)
    }

    companion object {
        private val TOGGLE_CRASHED_INTERVAL = TimeUnit.SECONDS.toMillis(10)

        private val UNBIND_HOLD_MS = TimeUnit.SECONDS.toMillis(90)
    }
}
