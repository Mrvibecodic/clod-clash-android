package com.github.kr328.clash.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.subscription.reportSubscriptionAlerts
import com.github.kr328.clash.service.util.UpdateFailures
import com.github.kr328.clash.service.util.UpdateSchedule
import com.github.kr328.clash.service.util.displayProfileName
import com.github.kr328.clash.service.util.humanizeUpdateFailure
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import com.github.kr328.clash.service.util.sendProfileUpdateStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProfileUpdateWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val context: Context = applicationContext

    override suspend fun doWork(): Result {
        val uuid = inputData.getString(KEY_UUID)?.let(UUID::fromString) ?: return Result.failure()
        val periodic = inputData.getBoolean(KEY_PERIODIC, false)

        if (!updating.add(uuid)) {
            Log.i("Update of $uuid already running")

            return Result.success()
        }

        try {
            createChannels()

            return run(uuid, periodic)
        } finally {
            updating.remove(uuid)
        }
    }

    private suspend fun run(uuid: UUID, periodic: Boolean): Result {
        val imported = ImportedDao().queryByUUID(uuid)

        if (imported == null) {
            WorkManager.getInstance(context).cancelWorkById(id)

            return Result.failure()
        }

        val name = context.displayProfileName(imported.uuid, imported.name, imported.nameManual)

        context.sendProfileUpdateStarted(imported.uuid)

        val result = try {
            val failedProviders = processing(name) {
                ProfileProcessor.update(context, imported.uuid)
            }

            completed(imported.uuid, context.displayProfileName(imported.uuid, imported.name, imported.nameManual), failedProviders)

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(imported.uuid, name, e.message ?: "Unknown")

            if (periodic && UpdateSchedule.retryWithinPeriod(imported.interval, runAttemptCount + 1)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        try {
            context.reportSubscriptionAlerts(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Subscription alerts of $uuid: $e", e)
        }

        if (result is Result.Success) {
            val stored = ImportedDao().queryByUUID(uuid) ?: imported

            if (!periodic || stored.interval != imported.interval) {
                ProfileUpdates.schedule(context, stored)
            }
        }

        return result
    }

    private fun createChannels() {
        NotificationManagerCompat.from(context).apply {
            deleteNotificationChannel(LEGACY_SERVICE_CHANNEL)

            createNotificationChannelsCompat(
                listOf(
                    NotificationChannelCompat.Builder(
                        STATUS_CHANNEL,
                        NotificationManagerCompat.IMPORTANCE_LOW
                    ).setName(context.getString(R.string.profile_process_status)).build(),
                    NotificationChannelCompat.Builder(
                        RESULT_CHANNEL,
                        NotificationManagerCompat.IMPORTANCE_DEFAULT
                    ).setName(context.getString(R.string.profile_process_result)).build(),
                    NotificationChannelCompat.Builder(
                        ERROR_CHANNEL,
                        NotificationManagerCompat.IMPORTANCE_DEFAULT
                    ).setName(context.getString(R.string.profile_process_error)).build()
                )
            )
        }
    }

    private suspend inline fun <T> processing(name: String, block: () -> T): T {
        val id = UndefinedIds.next()

        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setContentTitle(context.getString(R.string.profile_updating))
            .setContentText(name)
            .setColor(context.getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(STATUS_CHANNEL)
            .build()

        NotificationManagerCompat.from(context)
            .notify(id, notification)
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                NotificationManagerCompat.from(context)
                    .cancel(id)
            }
        }
    }

    private fun resultBuilder(id: Int, uuid: UUID, channel: String): NotificationCompat.Builder {
        val intent = PendingIntent.getActivity(
            context,
            id,
            Intent().setComponent(Components.PROPERTIES_ACTIVITY).setUUID(uuid),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        return NotificationCompat.Builder(context, channel)
            .setColor(context.getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(RESULT_CHANNEL)
    }

    private fun completed(uuid: UUID, name: String, failedProviders: List<String>) {
        val store = ServiceStore(context)

        val warning = failedProviders.takeIf { it.isNotEmpty() }
            ?.let { context.getString(R.string.format_update_partial, name, it.joinToString(", ")) }

        if (warning != null) {
            Log.w("Update of $uuid: providers not downloaded: ${failedProviders.joinToString(", ")}")
        }

        val plan = UpdateOutcome.plan(
            partial = warning != null,
            notifyErrors = store.notifyProfileErrors,
            notifyUpdates = store.notifyProfileUpdates,
        )

        if (plan != null) {
            val title = when (plan.kind) {
                UpdateOutcome.Kind.Partial -> R.string.update_partially
                else -> R.string.update_successfully
            }

            val content = warning ?: context.getString(R.string.format_update_complete, name)

            post(
                uuid,
                plan.kind,
                if (plan.error) ERROR_CHANNEL else RESULT_CHANNEL,
                context.getString(title),
                content,
            )
        }

        context.sendProfileUpdateCompleted(uuid, warning)
    }

    private fun failed(uuid: UUID, name: String, reason: String) {
        Log.w("Update of $uuid failed: ${Redact.text(reason)}")

        if (ServiceStore(context).notifyProfileErrors) {
            post(
                uuid,
                UpdateOutcome.Kind.Failure,
                ERROR_CHANNEL,
                context.getString(R.string.update_failure),
                context.getString(
                    R.string.format_update_failure,
                    name,
                    context.humanizeUpdateFailure(reason)
                        ?.let { human -> UpdateFailures.detail(reason)?.let { "$human: $it" } ?: human }
                        ?: Redact.text(reason),
                ),
            )
        }

        context.sendProfileUpdateFailed(uuid, reason)
    }

    private fun post(
        uuid: UUID,
        kind: UpdateOutcome.Kind,
        channel: String,
        title: String,
        content: String,
    ) {
        val manager = NotificationManagerCompat.from(context)

        UpdateOutcome.replaced(uuid, kind).forEach(manager::cancel)

        val id = UpdateOutcome.id(uuid, kind)

        manager.notify(
            id,
            resultBuilder(id, uuid, channel)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .build(),
        )
    }

    companion object {
        val updating: MutableSet<UUID> = Collections.newSetFromMap(ConcurrentHashMap())

        fun input(imported: Imported, periodic: Boolean): Data =
            workDataOf(KEY_UUID to imported.uuid.toString(), KEY_PERIODIC to periodic)

        private const val KEY_UUID = "uuid"
        private const val KEY_PERIODIC = "periodic"

        private const val LEGACY_SERVICE_CHANNEL = "profile_service_channel"
        private const val STATUS_CHANNEL = "profile_status_channel"
        private const val RESULT_CHANNEL = "profile_result_channel"
        private const val ERROR_CHANNEL = "profile_error_channel"
    }
}
