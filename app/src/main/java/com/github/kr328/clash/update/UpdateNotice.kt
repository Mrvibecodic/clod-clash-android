package com.github.kr328.clash.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Notifications
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.activeLocalProxyPort
import com.github.kr328.clash.service.util.withStoredLocale
import com.github.kr328.clash.store.AppStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.R as ServiceR

object UpdateNotice {
    const val ACTION_CHECK_UPDATE = "com.github.kr328.clash.action.CHECK_UPDATE"
    const val EXTRA_PUSHED_VERSION_CODE = "clod.update.pushed_version_code"

    private const val WORK_NAME = "app_update_notice"
    private const val CHANNEL = "app_update_channel"
    private const val NOTIFICATION_ID = Notifications.APP_UPDATE

    private const val EXTRA_VERSION_CODE = "clod.update.version_code"
    private const val EXTRA_PRERELEASE = "clod.update.prerelease"

    private const val CHECK_INTERVAL_HOURS = 3L
    private const val ANNOUNCED_KEPT = 8

    data class Shown(val versionCode: Long, val prerelease: Boolean)

    data class Plan(val withdraw: Boolean, val post: Long?)

    // Один пуш на версию: пропавший сам (перезагрузка, смахнули) не возвращается.
    internal fun plan(target: Long?, shown: Long?, announced: Set<Long>): Plan = Plan(
        withdraw = shown != null && shown != target,
        post = target?.takeIf { it != shown && it !in announced },
    )

    internal fun announce(announced: List<Long>, versionCode: Long): List<Long> =
        (announced - versionCode + versionCode).takeLast(ANNOUNCED_KEPT)

    // Только из :background — там живёт WorkManager.
    fun onBackgroundStart(context: Context) {
        val installed = Updater.currentVersionCode(context)

        withdrawIf(context) { it.versionCode <= installed }

        val request = PeriodicWorkRequestBuilder<CheckWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS).build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun withdraw(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun withdrawIf(context: Context, matches: (Shown) -> Boolean) {
        shown(context)?.takeIf(matches)?.let { withdraw(context) }
    }

    fun shown(context: Context): Shown? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null

        val extras = runCatching { manager.activeNotifications }
            .getOrNull()
            ?.firstOrNull { it.id == NOTIFICATION_ID && it.tag == null }
            ?.notification
            ?.extras
            ?: return null

        val versionCode = extras.getLong(EXTRA_VERSION_CODE, 0)
        if (versionCode <= 0) return null

        return Shown(versionCode, extras.getBoolean(EXTRA_PRERELEASE, false))
    }

    private fun canPost(manager: NotificationManagerCompat): Boolean =
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannelCompat(CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE

    private fun post(base: Context, available: Updater.Available): Boolean {
        val context = base.withStoredLocale()

        val manager = NotificationManagerCompat.from(context)

        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(DesignR.string.clod_update_notify_channel))
                .build()
        )

        if (!canPost(manager)) return false

        val version = available.manifest.version
        val prerelease = '-' in version
        val title = if (prerelease) {
            DesignR.string.clod_update_notify_prerelease
        } else {
            DesignR.string.clod_update_notify_stable
        }

        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_CHECK_UPDATE)
                .putExtra(EXTRA_PUSHED_VERSION_CODE, available.manifest.versionCode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(ServiceR.drawable.ic_logo_service)
            .setColor(context.getColorCompat(ServiceR.color.color_clash))
            .setContentTitle(context.getString(title, version))
            .setContentText(context.getString(DesignR.string.clod_update_notify_text))
            .setContentIntent(open)
            .addAction(0, context.getString(DesignR.string.clod_update_install), open)
            .setAutoCancel(true)
            .addExtras(
                Bundle().apply {
                    putLong(EXTRA_VERSION_CODE, available.manifest.versionCode)
                    putBoolean(EXTRA_PRERELEASE, prerelease)
                },
            )
            .build()

        // Пока шла проверка, человек мог выключить пуши, пропустить версию или
        // выключить пре-релизы — сверяем всё это прямо перед показом.
        val app = AppStore.readFromBackground(context)
        val wanted = ServiceStore(context).notifyAppUpdates &&
            app.skippedVersionCode != available.manifest.versionCode &&
            (!prerelease || app.prereleaseChannel)

        if (!wanted) return false

        manager.notify(NOTIFICATION_ID, notification)

        return true
    }

    class CheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val context = applicationContext
            val settings = ServiceStore(context)

            if (!settings.notifyAppUpdates) {
                withdraw(context)

                return Result.success()
            }

            if (!canPost(NotificationManagerCompat.from(context))) return Result.success()

            val app = AppStore.readFromBackground(context)

            val checked = try {
                Updater.check(context, app.prereleaseChannel, context.activeLocalProxyPort())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }

            val available = checked.getOrElse {
                if ((it as? Updater.UpdateException)?.kind != Updater.UpdateException.Kind.NoBuild) {
                    Log.w("UpdateNotice: проверка не удалась", it)

                    return Result.success()
                }

                null
            }

            val target = available?.takeIf { it.manifest.versionCode != app.skippedVersionCode }
            val announced = settings.appUpdateAnnounced
            val plan = plan(target?.manifest?.versionCode, shown(context)?.versionCode, announced.toSet())

            if (plan.withdraw) withdraw(context)

            if (plan.post != null && target != null && post(context, target)) {
                settings.appUpdateAnnounced = announce(announced, plan.post)
            }

            return Result.success()
        }
    }
}
