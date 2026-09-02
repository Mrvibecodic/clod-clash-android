package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.compat.tryStartForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.channels.Channel

class StaticNotificationModule(service: Service) : Module<Unit>(service) {
    private val builder = NotificationCompat.Builder(service, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_service)
        .setOngoing(true)
        .setColor(service.getColorCompat(R.color.color_clash))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                service,
                R.id.nf_clash_status,
                Intent().setComponent(Components.MAIN_ACTIVITY)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        )
        .addAction(0, service.getText(R.string.clod_notification_stop), stopIntent(service))

    override suspend fun run() {
        val events = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
            addAction(Intents.ACTION_CLASH_STARTED)
        }

        var ready = false

        while (true) {
            if (events.receive().action == Intents.ACTION_CLASH_STARTED) {
                ready = true
            }

            val profileName = StatusProvider.currentProfile ?: "Not selected"

            val notification = builder
                .setContentTitle(profileName)
                .setContentText(service.getText(if (ready) R.string.running else R.string.loading))
                .build()

            service.startForegroundCompat(R.id.nf_clash_status, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "clash_status_channel"

        fun stopIntent(service: Service): PendingIntent {
            return PendingIntent.getBroadcast(
                service,
                R.id.nf_clash_status,
                Intent(Intents.ACTION_CLASH_REQUEST_STOP)
                    .setPackage(service.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                pendingIntentFlags(PendingIntent.FLAG_CANCEL_CURRENT)
            )
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
            val notification =
                NotificationCompat.Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_service)
                    .setOngoing(true)
                    .setColor(service.getColorCompat(R.color.color_clash))
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
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

        fun cancelStartFailed(service: Service) {
            NotificationManagerCompat.from(service).cancel(R.id.nf_clash_start_failed)
        }

        fun notifyStartFailed(service: Service, rawReason: String, title: Int = R.string.clod_start_failed) {
            val reason = Redact.text(rawReason)

            val notification =
                NotificationCompat.Builder(service, CHANNEL_ID)
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
                NotificationManagerCompat.from(service).notify(R.id.nf_clash_start_failed, notification)
            }
        }
    }
}
