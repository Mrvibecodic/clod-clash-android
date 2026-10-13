package com.github.kr328.clash.service.clash.module

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Process
import androidx.core.content.getSystemService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.tryStartForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.ServiceLog
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

class StaticNotificationModule(service: Service) : Module<Unit>(service) {
    private val builder = ongoingBuilder(service, R.id.nf_clash_status)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    override suspend fun run() = coroutineScope {
        val events = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
            addAction(Intents.ACTION_CLASH_STARTED)
        }

        val guard = ticker(STATUS_GUARD_MS)
        val restored = StatusGuard(service)

        // Заново запущенный модуль застаёт уже поднявшуюся сессию
        var ready = StatusProvider.serviceReady
        var shown: Notification? = null

        fun post() {
            val profileName = StatusProvider.currentProfile ?: service.getString(R.string.launch_name)

            val notification = builder
                .setContentTitle(profileName)
                .setContentText(service.getText(if (ready) R.string.running else R.string.loading))
                .build()

            shown = notification

            // Служба на переднем плане со старта сессии: повторный startForeground
            // заново проверяет запрет фонового запуска и роняет модуль
            try {
                NotificationManagerCompat.from(service).notify(R.id.nf_clash_status, notification)
            } catch (e: Exception) {
                Log.w("Update status notification: $e", e)
            }
        }

        try {
            if (ready) post()

            while (true) {
                select<Unit> {
                    events.onReceive {
                        if (it.action == Intents.ACTION_CLASH_STARTED) {
                            ready = true
                        }

                        post()
                    }
                    guard.onReceive {
                        shown?.let(restored::check)
                    }
                }
            }
        } finally {
            cancelStatus(service)
        }
    }

    // Уведомление службы на месте: система могла снять с неё передний план, а
    // человек — смахнуть уведомление (Android 14+); тогда оно возвращается.
    // В журнал — только смена исхода, а не каждая проверка
    class StatusGuard(private val service: Service) {
        private var last: String? = null

        fun check(notification: Notification) {
            val outcome = try {
                restore(notification)
            } catch (e: Exception) {
                Log.w("Status notification check: $e", e)

                return
            }

            if (outcome != null && outcome != last) {
                ServiceLog.mark("status notification: $outcome")
            }

            last = outcome
        }

        private fun restore(notification: Notification): String? {
            @Suppress("DEPRECATION")
            val foreground = service.getSystemService<ActivityManager>()
                ?.getRunningServices(Int.MAX_VALUE)
                ?.firstOrNull { it.service.className == service.javaClass.name && it.pid == Process.myPid() }
                ?.foreground ?: true

            if (!foreground) {
                return if (service.tryStartForegroundCompat(R.id.nf_clash_status, notification))
                    "foreground restored"
                else
                    "foreground denied"
            }

            // Уведомления запрещены человеком: возвращать нечего
            val manager = NotificationManagerCompat.from(service)
            val channel = manager.getNotificationChannelCompat(CHANNEL_ID)

            if (!manager.areNotificationsEnabled() || channel?.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
                return "blocked"
            }

            val shown = service.getSystemService<NotificationManager>()
                ?.activeNotifications
                ?.any { it.id == R.id.nf_clash_status } ?: true

            if (shown) return null

            manager.notify(R.id.nf_clash_status, notification)

            return "notification restored"
        }
    }

    companion object {
        const val CHANNEL_ID = "clash_status_channel"

        // Остановка и сбои — со звуком и отдельно от постоянного уведомления,
        // которое часто выключают
        private const val STOP_CHANNEL_ID = "clash_stop_channel"

        const val STATUS_GUARD_MS = 60_000L

        private fun stopIntent(service: Service, requestCode: Int): PendingIntent {
            return PendingIntent.getBroadcast(
                service,
                requestCode,
                Intent(Intents.ACTION_CLASH_REQUEST_STOP)
                    .setPackage(service.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                pendingIntentFlags(PendingIntent.FLAG_CANCEL_CURRENT)
            )
        }

        fun ongoingBuilder(service: Service, stopRequestCode: Int): NotificationCompat.Builder {
            return NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_service)
                .setOngoing(true)
                .setColor(service.getColorCompat(R.color.color_clash))
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(
                    PendingIntent.getActivity(
                        service,
                        R.id.nf_clash_status,
                        Intent().setComponent(Components.MAIN_ACTIVITY)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                    )
                )
                .addAction(0, service.getText(R.string.clod_notification_stop), stopIntent(service, stopRequestCode))
        }

        fun createNotificationChannel(service: Service) {
            NotificationManagerCompat.from(service).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(service.getText(R.string.clash_service_status_channel)).build()
            )
        }

        fun notifyLoadingNotification(service: Service): Boolean {
            val notification = ongoingBuilder(service, R.id.nf_clash_loading_stop)
                .setContentTitle(service.getText(R.string.loading))
                .build()

            return service.tryStartForegroundCompat(R.id.nf_clash_status, notification)
        }

        fun notifyRejectedNotification(service: Service): Boolean {
            val notification =
                NotificationCompat.Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_service)
                    .setColor(service.getColorCompat(R.color.color_clash))
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentTitle(service.getText(R.string.loading))
                    .build()

            return service.tryStartForegroundCompat(R.id.nf_clash_reject, notification)
        }

        // Обновление, пришедшее после остановки службы, повисло бы уведомлением без
        // неё; уведомление живой службы система отменить не даёт
        fun cancelStatus(service: Service) {
            NotificationManagerCompat.from(service).cancel(R.id.nf_clash_status)
        }

        fun cancelStartFailed(service: Service) {
            NotificationManagerCompat.from(service).cancel(R.id.nf_clash_start_failed)
        }

        fun notifyStartFailed(service: Service, rawReason: String, title: Int = R.string.clod_start_failed) {
            val reason = Redact.text(rawReason)

            val notification =
                NotificationCompat.Builder(service, STOP_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_service)
                    .setColor(service.getColorCompat(R.color.color_clash))
                    .setAutoCancel(true)
                    .setContentTitle(service.getText(title))
                    .setContentText(reason)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            service,
                            R.id.nf_clash_status,
                            Intent().setComponent(Components.MAIN_ACTIVITY)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                        )
                    )
                    .build()

            runCatching {
                val manager = NotificationManagerCompat.from(service)

                manager.createNotificationChannel(
                    NotificationChannelCompat.Builder(STOP_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                        .setName(service.getText(R.string.clod_stop_channel))
                        .build()
                )

                manager.notify(R.id.nf_clash_start_failed, notification)
            }
        }
    }
}
