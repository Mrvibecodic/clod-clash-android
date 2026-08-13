package com.github.kr328.clash.remote

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import java.util.*

class Broadcasts(private val context: Application) {
    interface Observer {
        fun onServiceRecreated()
        fun onStarted()
        fun onStopped(cause: String?)
        fun onProfileChanged()
        fun onProfileUpdateCompleted(uuid: UUID?)
        fun onProfileUpdateFailed(uuid: UUID?, reason: String?)
        fun onProfileLoaded()
    }

    var clashRunning: Boolean = false

    private var registered = false
    private val receivers = mutableListOf<Observer>()
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != context?.packageName)
                return

            when (intent?.action) {
                Intents.ACTION_SERVICE_RECREATED -> {
                    clashRunning = false

                    receivers.forEach {
                        it.onServiceRecreated()
                    }
                }
                Intents.ACTION_CLASH_STARTED -> {
                    clashRunning = true

                    receivers.forEach {
                        it.onStarted()
                    }
                }
                Intents.ACTION_CLASH_STOPPED -> {
                    clashRunning = false

                    receivers.forEach {
                        it.onStopped(intent.getStringExtra(Intents.EXTRA_STOP_REASON))
                    }
                }
                Intents.ACTION_PROFILE_CHANGED ->
                    receivers.forEach {
                        it.onProfileChanged()
                    }
                Intents.ACTION_PROFILE_UPDATE_COMPLETED ->
                    receivers.forEach {
                        it.onProfileUpdateCompleted(
                            UUID.fromString(intent.getStringExtra(Intents.EXTRA_UUID)))
                    }
                Intents.ACTION_PROFILE_UPDATE_FAILED ->
                    receivers.forEach {
                        it.onProfileUpdateFailed(
                            UUID.fromString(intent.getStringExtra(Intents.EXTRA_UUID)),
                            intent.getStringExtra(Intents.EXTRA_FAIL_REASON))
                    }
                Intents.ACTION_PROFILE_LOADED -> {
                    receivers.forEach {
                        it.onProfileLoaded()
                    }
                }
            }
        }
    }

    fun addObserver(observer: Observer) {
        receivers.add(observer)
    }

    fun removeObserver(observer: Observer) {
        receivers.remove(observer)
    }

    /**
     * Зовётся каждый раз, когда приложение выходит на передний план.
     *
     * Два разных дела, которые раньше стояли под одним условием: подписка на
     * события нужна ОДИН раз (поле `registered` не ставилось никогда, поэтому
     * один и тот же приёмник регистрировался заново на каждый показ, и каждое
     * событие приходило столько раз, сколько было показов за жизнь процесса),
     * а состояние ядра нужно перечитывать ВСЕГДА — пока экран был закрыт,
     * службу мог убить телефон, и сообщить об этом было некому.
     *
     * Снимать подписку при уходе в фон мы НЕ станем, хотя парная `unregister`
     * для этого и писалась: она не работала ни дня, и на её бездействие уже
     * опираются — например, `AccessControlActivity` после смены списка
     * приложений ждёт остановки службы циклом по `clashRunning`, а этот цикл
     * переживает уход человека на домашний экран. Широковещания у нас свои же
     * и внутри процесса, так что постоянная подписка ничего не стоит.
     */
    fun register() {
        if (!registered) {
            try {
                context.registerReceiverCompat(broadcastReceiver, IntentFilter().apply {
                    addAction(Intents.ACTION_SERVICE_RECREATED)
                    addAction(Intents.ACTION_CLASH_STARTED)
                    addAction(Intents.ACTION_CLASH_STOPPED)
                    addAction(Intents.ACTION_PROFILE_CHANGED)
                    addAction(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
                    addAction(Intents.ACTION_PROFILE_UPDATE_FAILED)
                    addAction(Intents.ACTION_PROFILE_LOADED)
                })

                registered = true
            } catch (e: Exception) {
                Log.w("Register global receiver: $e", e)
            }
        }

        refreshRunning()
    }

    /**
     * Спрашиваем состояние у процесса службы, а не помним своё.
     *
     * Ровно на этом ломаются соседние клиенты: экран рисует «подключено» из
     * сохранённой переменной, туннеля при этом уже нет.
     */
    private fun refreshRunning() {
        clashRunning = StatusClient(context).currentProfile() != null
    }
}