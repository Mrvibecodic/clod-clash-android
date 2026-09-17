package com.github.kr328.clash

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.log.LogcatCache
import com.github.kr328.clash.log.LogcatWriter
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.logsDir
import com.github.kr328.clash.util.unbindServiceSilent
import com.github.kr328.clash.util.withAppLocale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.*

class LogcatService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Default), IInterface {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withAppLocale())
    }

    private val cache = LogcatCache()

    private val channel = Channel<LogMessage>(CACHE_CAPACITY)

    private val observer = object : ILogObserver {
        private var overflowed = false

        override fun newItem(log: LogMessage) {
            runCatching {
                if (channel.trySend(log).isSuccess) {
                    overflowed = false
                } else if (!overflowed) {
                    overflowed = true

                    Log.w("Logcat buffer overflow, messages dropped")
                }
            }.onFailure {
                Log.w("Receive log item: $it", it)
            }
        }
    }

    @Volatile
    private var remote: IBinder? = null

    @Volatile
    private var finished = false

    // Привязка переживает смерть фонового процесса: система поднимает его заново
    // и зовёт onServiceConnected ещё раз — запись продолжается в тот же файл
    private val connection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null

            channel.trySend(notice("background process died, waiting for restart"))
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            subscribe(service ?: return stopSelf())
        }
    }

    override fun onCreate() {
        super.onCreate()

        running = true

        createNotificationChannel()

        showNotification()

        startWriter()

        bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        cancel()

        unbindServiceSilent(connection)

        stopForeground(true)

        running = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return this.asBinder()
    }

    override fun asBinder(): IBinder {
        return object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface {
                return this@LogcatService
            }
        }
    }

    suspend fun snapshot(full: Boolean): LogcatCache.Snapshot? {
        return cache.snapshot(full)
    }

    private fun subscribe(binder: IBinder) {
        if (!binder.isBinderAlive)
            return stopSelf()

        if (finished) return

        remote = binder

        launch(Dispatchers.IO) {
            try {
                val clash = binder.unwrap(IRemoteService::class).clash()

                clash.setLogObserver(observer)

                // Запись могла закончиться, пока шёл вызов: её отписка тогда
                // уже позади, и эта подписка осталась бы без хозяина
                if (!isActive || finished) {
                    clash.setLogObserver(null)
                }
            } catch (e: Exception) {
                Log.w("Subscribe logcat: $e", e)
            }
        }
    }

    private fun startWriter() {
        launch(Dispatchers.IO) {
            try {
                logsDir.mkdirs()

                LogcatWriter(this@LogcatService).use {
                    while (isActive) {
                        val msg = channel.receive()

                        if (!it.appendMessage(msg)) {
                            val last = notice("log file size limit reached, recording stopped")

                            it.appendLast(last)

                            cache.append(last)

                            break
                        }

                        cache.append(msg)
                    }
                }
            } catch (e: IOException) {
                Log.e("Write log file: $e", e)
            } finally {
                withContext(NonCancellable) {
                    finished = true

                    try {
                        remote?.takeIf { it.isBinderAlive }
                            ?.unwrap(IRemoteService::class)?.clash()?.setLogObserver(null)
                    } catch (e: Exception) {
                        Log.w("Unsubscribe logcat: $e", e)
                    }

                    // Запись закончилась сама: экран записи держит свою привязку, и
                    // stopSelf при ней службу не уничтожает — всё, что держит служба,
                    // отпускается здесь. При остановке извне это делает onDestroy
                    if (this@LogcatService.isActive) {
                        unbindServiceSilent(connection)

                        stopForeground(true)

                        running = false

                        stopSelf()
                    }
                }
            }
        }
    }

    private fun notice(text: String): LogMessage =
        LogMessage(LogMessage.Level.Warning, "[APP] $text", Date())

    private fun createNotificationChannel() {
        NotificationManagerCompat.from(this)
            .createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(com.github.kr328.clash.design.R.string.clash_logcat)).build()
            )
    }

    private fun showNotification() {
        val notification = NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
            .setColor(getColorCompat(com.github.kr328.clash.service.R.color.color_clash))
            .setContentTitle(getString(com.github.kr328.clash.design.R.string.clash_logcat))
            .setContentText(getString(com.github.kr328.clash.service.R.string.running))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    R.id.nf_logcat_status,
                    LogcatActivity::class.intent
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )
            .build()

        startForegroundCompat(R.id.nf_logcat_status, notification)
    }

    companion object {
        private const val CHANNEL_ID = "clash_logcat_channel"
        private const val CACHE_CAPACITY = 128

        @Volatile
        var running: Boolean = false
    }
}
