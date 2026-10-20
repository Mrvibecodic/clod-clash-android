package com.github.kr328.clash.remote

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.sendControlBroadcastSelf
import java.util.*

class Broadcasts(private val context: Application) {
    interface Observer {
        fun onServiceRecreated()
        fun onStarting(stage: String?)
        fun onStarted()
        fun onStopped(cause: String?)
        fun onProfileChanged(uuid: UUID?)
        fun onProfileUpdateStarted(uuid: UUID?)
        fun onProfileUpdateCompleted(uuid: UUID?, warning: String?)
        fun onProfileUpdateFailed(uuid: UUID?, reason: String?)
        fun onProfileLoaded()
        fun onProfileLoadFailed(uuid: UUID?, reason: String?)
        fun onFreezeMarksChanged(uuid: UUID?) {}
        fun onHiddenServersChanged() {}
        fun onTunnelAppsChanged() {}
    }

    @Volatile
    var clashRunning: Boolean = false
        private set

    // Сессия жива — запускается или работает. Служба читает настройки с самого
    // старта сессии, а не с готовности туннеля: экраны настроек запираются по этому.
    @Volatile
    var clashActive: Boolean = false
        private set

    // Счёт рассылок о состоянии службы: ответ службы, прочитанный мимо рассылок,
    // применяется, только если за время чтения ни одна не пришла — рассылка свежее.
    @Volatile
    var epoch: Int = 0
        private set

    // Ответ службы (null — службы нет) к обоим признакам разом; since — epoch,
    // снятый перед чтением ответа.
    fun apply(status: StatusClient.Status?, since: Int) {
        if (since != epoch) return

        set(status)
    }

    private fun set(status: StatusClient.Status?) {
        clashRunning = status?.running == true
        clashActive = status?.let { it.running || it.starting } == true

        releaseHeldStop()
    }

    // Рассылку стопа слышит только поднятая сессия: стоп сразу после своего
    // старта ждёт её, но не дольше HOLD_MS — иначе погасил бы позже чужой
    // старт (постоянный VPN)
    @Volatile
    var stopRequestedAt: Long? = null

    @Volatile
    private var stopHeldAt: Long? = null

    @Volatile
    private var startRequestedAt: Long? = null

    fun startRequested() {
        listen()

        stopHeldAt = null
        stopRequestedAt = null
        startRequestedAt = SystemClock.elapsedRealtime()
    }

    fun startAbandoned() {
        stopHeldAt = null
        stopRequestedAt = null
        startRequestedAt = null
    }

    fun activeOrStarting(running: Boolean): Boolean = running || clashActive || fresh(startRequestedAt)

    fun requestStop(at: Long = SystemClock.elapsedRealtime()) {
        stopRequestedAt = at

        if (holdStop(clashActive, fresh(startRequestedAt), registered)) {
            stopHeldAt = at
        } else {
            sendStop()
        }

        startRequestedAt = null
    }

    fun takeHeldStop(): Boolean {
        val held = fresh(stopHeldAt)

        stopHeldAt = null

        return held
    }

    private fun fresh(at: Long?): Boolean = at != null && SystemClock.elapsedRealtime() - at < HOLD_MS

    private fun releaseHeldStop() {
        if (clashActive) startRequestedAt = null

        if (clashActive && takeHeldStop()) sendStop()
    }

    private fun sendStop() {
        context.sendControlBroadcastSelf(Intent(Intents.ACTION_CLASH_REQUEST_STOP))
    }

    private var registered = false
    private val receivers = mutableListOf<Observer>()
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != context?.packageName)
                return

            when (intent?.action) {
                Intents.ACTION_SERVICE_RECREATED -> {
                    refreshRunning()

                    receivers.forEach {
                        it.onServiceRecreated()
                    }
                }
                Intents.ACTION_CLASH_STARTING -> {
                    epoch++
                    clashRunning = false
                    clashActive = true

                    releaseHeldStop()

                    receivers.forEach {
                        it.onStarting(intent.getStringExtra(Intents.EXTRA_STAGE))
                    }
                }
                Intents.ACTION_CLASH_STARTED -> {
                    epoch++
                    clashRunning = true
                    clashActive = true

                    releaseHeldStop()

                    receivers.forEach {
                        it.onStarted()
                    }
                }
                Intents.ACTION_CLASH_STOPPED -> {
                    stopHeldAt = null
                    startRequestedAt = null

                    refreshRunning()

                    receivers.forEach {
                        it.onStopped(intent.getStringExtra(Intents.EXTRA_STOP_REASON))
                    }
                }
                Intents.ACTION_PROFILE_CHANGED ->
                    receivers.forEach {
                        it.onProfileChanged(intent.parseUUID())
                    }
                Intents.ACTION_PROFILE_UPDATE_STARTED ->
                    receivers.forEach {
                        it.onProfileUpdateStarted(intent.parseUUID())
                    }
                Intents.ACTION_PROFILE_UPDATE_COMPLETED ->
                    receivers.forEach {
                        it.onProfileUpdateCompleted(
                            intent.parseUUID(),
                            intent.getStringExtra(Intents.EXTRA_WARNING))
                    }
                Intents.ACTION_PROFILE_UPDATE_FAILED ->
                    receivers.forEach {
                        it.onProfileUpdateFailed(
                            intent.parseUUID(),
                            intent.getStringExtra(Intents.EXTRA_FAIL_REASON))
                    }
                Intents.ACTION_PROFILE_LOADED -> {
                    receivers.forEach {
                        it.onProfileLoaded()
                    }
                }
                Intents.ACTION_PROFILE_LOAD_FAILED -> {
                    receivers.forEach {
                        it.onProfileLoadFailed(
                            intent.parseUUID(),
                            intent.getStringExtra(Intents.EXTRA_FAIL_REASON))
                    }
                }
                Intents.ACTION_FREEZE_MARKS_CHANGED -> {
                    receivers.forEach {
                        it.onFreezeMarksChanged(intent.parseUUID())
                    }
                }
                Intents.ACTION_HIDDEN_SERVERS_CHANGED -> {
                    receivers.forEach {
                        it.onHiddenServersChanged()
                    }
                }
                Intents.ACTION_TUNNEL_APPS_CHANGED -> {
                    receivers.forEach {
                        it.onTunnelAppsChanged()
                    }
                }
            }
        }
    }

    private fun Intent.parseUUID(): UUID? {
        val value = getStringExtra(Intents.EXTRA_UUID) ?: return null

        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    fun addObserver(observer: Observer) {
        receivers.add(observer)
    }

    fun removeObserver(observer: Observer) {
        receivers.remove(observer)
    }

    fun register() {
        listen()

        refreshRunning()
    }

    // Только приёмник, без вопроса службе: процесс без окна (плитка, автозапуск)
    // запросил старт и должен услышать, что сессия поднялась
    private fun listen() {
        if (!registered) {
            try {
                context.registerReceiverCompat(broadcastReceiver, IntentFilter().apply {
                    addAction(Intents.ACTION_SERVICE_RECREATED)
                    addAction(Intents.ACTION_CLASH_STARTING)
                    addAction(Intents.ACTION_CLASH_STARTED)
                    addAction(Intents.ACTION_CLASH_STOPPED)
                    addAction(Intents.ACTION_PROFILE_CHANGED)
                    addAction(Intents.ACTION_PROFILE_UPDATE_STARTED)
                    addAction(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
                    addAction(Intents.ACTION_PROFILE_UPDATE_FAILED)
                    addAction(Intents.ACTION_PROFILE_LOADED)
                    addAction(Intents.ACTION_PROFILE_LOAD_FAILED)
                    addAction(Intents.ACTION_FREEZE_MARKS_CHANGED)
                    addAction(Intents.ACTION_HIDDEN_SERVERS_CHANGED)
                    addAction(Intents.ACTION_TUNNEL_APPS_CHANGED)
                }, Permissions.RECEIVE_SELF_BROADCASTS)

                registered = true
            } catch (e: Exception) {
                Log.w("Register global receiver: $e", e)
            }
        }
    }

    // Туннель живёт только в процессе :background. Нет процесса — ответ известен
    // без вопроса, а вопрос поднял бы этот процесс, и главный поток, откуда сюда
    // приходят при каждом появлении приложения, ждал бы его запуска.
    private fun refreshRunning() {
        epoch++

        set(if (backgroundAlive()) StatusClient(context).status() else null)
    }

    private fun backgroundAlive(): Boolean {
        val processes = context.getSystemService<ActivityManager>()?.runningAppProcesses ?: return true
        val name = context.packageName + BACKGROUND_PROCESS

        return processes.any { it.processName == name }
    }

    private companion object {
        const val BACKGROUND_PROCESS = ":background"
        const val HOLD_MS = 30_000L
    }
}

// Стоп откладывается, только пока сессия не поднята, этот процесс сам её
// только что запросил и услышит, что она поднялась: иначе стоп нужен сразу
internal fun holdStop(sessionActive: Boolean, ownStartPending: Boolean, listening: Boolean): Boolean =
    !sessionActive && ownStartPending && listening
