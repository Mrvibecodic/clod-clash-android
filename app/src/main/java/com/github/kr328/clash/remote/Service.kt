package com.github.kr328.clash.remote

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.util.unbindServiceSilent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Менеджеры RemoteService живут столько же, сколько он сам: запрашиваются один раз
// на соединение, а не лишней транзакцией на каждый вызов.
class RemoteHandle(service: IRemoteService) {
    val clash: IClashManager by lazy { service.clash() }
    val profile: IProfileManager by lazy { service.profile() }
}

class Service(private val context: Application, val crashed: () -> Unit) {
    val remote = Resource<RemoteHandle>()

    @Volatile
    var boundSince: Long = 0
        private set

    private val inFlight = AtomicInteger(0)

    // Привязка и отвязка решаются только на главном потоке — там же приходят
    // появление и уход приложения, поэтому отвязка не может обогнать привязку,
    // сделанную в то же мгновение.
    private val main = Handler(Looper.getMainLooper())

    private var unbindRequested = false

    private var unbindTimer: Job? = null

    fun beginOperation() {
        inFlight.incrementAndGet()
    }

    fun endOperation() {
        if (inFlight.decrementAndGet() == 0) {
            main.post {
                if (inFlight.get() == 0) unbindIfRequested()
            }
        }
    }

    fun requestUnbind() {
        if (inFlight.get() == 0) {
            return unbind()
        }

        unbindRequested = true

        unbindTimer?.cancel()
        unbindTimer = Global.launch(Dispatchers.Main) {
            delay(UNBIND_HOLD_MS)

            unbindIfRequested()
        }
    }

    private fun unbindIfRequested() {
        if (!unbindRequested) return

        unbindRequested = false

        unbind()
    }

    private val connection = object : ServiceConnection {
        private var lastCrashed: Long = -1

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            remote.set(RemoteHandle(service.unwrap(IRemoteService::class)))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote.set(null)

            if (System.currentTimeMillis() - lastCrashed < TOGGLE_CRASHED_INTERVAL) {
                giveUp()
            }

            lastCrashed = System.currentTimeMillis()
            Log.w("RemoteService killed or crashed")
        }

        // Служба падала раз за разом, и система её больше не поднимает.
        override fun onBindingDied(name: ComponentName?) {
            remote.set(null)

            giveUp()
        }
    }

    fun bind() {
        try {
            unbindTimer?.cancel()
            unbindRequested = false

            boundSince = System.currentTimeMillis()

            if (!context.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)) {
                Log.w("RemoteService bind refused")

                giveUp()
            }
        } catch (e: Exception) {
            giveUp()
        }
    }

    private fun unbind() {
        boundSince = 0

        context.unbindServiceSilent(connection)

        remote.set(null)
    }

    private fun giveUp() {
        unbind()

        crashed()
    }

    companion object {
        private val TOGGLE_CRASHED_INTERVAL = TimeUnit.SECONDS.toMillis(10)

        private val UNBIND_HOLD_MS = TimeUnit.SECONDS.toMillis(90)
    }
}
