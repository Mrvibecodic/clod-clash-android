package com.github.kr328.clash.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.subscription.reportSubscriptionAlerts
import com.github.kr328.clash.service.util.displayProfileName
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class ProfileWorker : BaseService() {
    private val jobs = ConcurrentLinkedQueue<Job>()

    override fun onCreate() {
        super.onCreate()

        createChannels()

        foreground()

        launch {
            delay(TimeUnit.SECONDS.toMillis(10))

            while (true) {
                jobs.poll()?.join() ?: break
            }

            stopSelf()
        }
    }

    override fun onDestroy() {
        stopForeground(true)

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                intent.uuid?.also {
                    val job = launch {
                        run(it)
                    }

                    jobs.add(job)
                }
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun run(uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        val name = displayProfileName(imported.uuid, imported.name)

        try {
            processing(name) {
                ProfileProcessor.update(this, imported.uuid, null)
            }

            completed(imported.uuid, displayProfileName(imported.uuid, imported.name))

            ProfileReceiver.scheduleNext(this, imported)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(imported.uuid, name, e.message ?: "Unknown")

            ProfileReceiver.scheduleRetry(this, imported)
        }

        try {
            reportSubscriptionAlerts(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Subscription alerts of $uuid: $e", e)
        }
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_service_status)).build(),
                NotificationChannelCompat.Builder(
                    STATUS_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_process_status)).build(),
                NotificationChannelCompat.Builder(
                    RESULT_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.profile_process_result)).build(),
                NotificationChannelCompat.Builder(
                    ERROR_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.profile_process_error)).build()
            )
        )
    }

    private fun foreground() {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.profile_updater))
            .setContentText(getString(R.string.running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForegroundCompat(R.id.nf_profile_worker, notification)
    }

    private suspend inline fun processing(name: String, block: () -> Unit) {
        val id = UndefinedIds.next()

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setContentTitle(getString(R.string.profile_updating))
            .setContentText(name)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(STATUS_CHANNEL)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(id, notification)
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                NotificationManagerCompat.from(applicationContext)
                    .cancel(id)
            }
        }
    }

    private fun resultBuilder(id: Int, uuid: UUID, channel: String): NotificationCompat.Builder {
        val intent = PendingIntent.getActivity(
            this,
            id,
            Intent().setComponent(Components.PROPERTIES_ACTIVITY).setUUID(uuid),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        return NotificationCompat.Builder(this, channel)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(RESULT_CHANNEL)
    }

    private fun completed(uuid: UUID, name: String) {
        if (ServiceStore(this).notifyProfileUpdates) {
            val id = uuid.hashCode()

            val notification = resultBuilder(id, uuid, RESULT_CHANNEL)
                .setContentTitle(getString(R.string.update_successfully))
                .setContentText(getString(R.string.format_update_complete, name))
                .build()

            NotificationManagerCompat.from(this)
                .notify(id, notification)
        }

        sendProfileUpdateCompleted(uuid)
    }

    private fun failed(uuid: UUID, name: String, reason: String) {
        if (ServiceStore(this).notifyProfileErrors) {
            val id = uuid.hashCode()

            val content = getString(R.string.format_update_failure, name, Redact.text(reason))

            val notification = resultBuilder(id, uuid, ERROR_CHANNEL)
                .setContentTitle(getString(R.string.update_failure))
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .build()

            NotificationManagerCompat.from(this)
                .notify(id, notification)
        }

        sendProfileUpdateFailed(uuid, reason)
    }

    companion object {
        private const val SERVICE_CHANNEL = "profile_service_channel"
        private const val STATUS_CHANNEL = "profile_status_channel"
        private const val RESULT_CHANNEL = "profile_result_channel"
        private const val ERROR_CHANNEL = "profile_error_channel"
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
