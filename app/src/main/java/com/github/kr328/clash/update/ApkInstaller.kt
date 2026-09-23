package com.github.kr328.clash.update

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.util.withAppLocale
import java.io.File
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.R as ServiceR

object ApkInstaller {
    private const val TAG = "ApkInstaller"

    private const val CONFIRM_CHANNEL = "update_confirm_channel"
    private const val CONFIRM_NOTIFICATION_ID = 0x7702

    const val ACTION_INSTALL_STATUS = "install_status"

    private const val ACTION_CONFIRM_DISMISSED = "update_confirm_dismissed"

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun requestPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }

        val sessionId = installer.createSession(params)

        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }

                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }

                val status = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent("${context.packageName}.$ACTION_INSTALL_STATUS")
                        .setPackage(context.packageName)
                        .setClass(context, ResultReceiver::class.java),
                    flags,
                )

                session.commit(status.intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(sessionId) }

            apk.delete()

            throw e
        }
    }

    private fun hasVisibleActivity(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()

        ActivityManager.getMyMemoryState(state)

        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun notifyConfirm(base: Context, confirm: Intent, session: Int): Boolean {
        val context = base.withAppLocale()

        val manager = NotificationManagerCompat.from(context)

        if (!manager.areNotificationsEnabled()) return false

        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                CONFIRM_CHANNEL,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            ).setName(context.getString(DesignR.string.clod_update_confirm_channel)).build()
        )

        val channel = manager.getNotificationChannelCompat(CONFIRM_CHANNEL)

        if (channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            return false
        }

        val notification = NotificationCompat.Builder(context, CONFIRM_CHANNEL)
            .setSmallIcon(ServiceR.drawable.ic_logo_service)
            .setContentTitle(context.getString(DesignR.string.clod_update_confirm_title))
            .setContentText(context.getString(DesignR.string.clod_update_confirm_text))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    CONFIRM_NOTIFICATION_ID,
                    confirm,
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    CONFIRM_NOTIFICATION_ID,
                    Intent(context, ResultReceiver::class.java)
                        .setAction("${context.packageName}.$ACTION_CONFIRM_DISMISSED")
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, session),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            .build()

        return runCatching { manager.notify(CONFIRM_NOTIFICATION_ID, notification) }.isSuccess
    }

    private fun finish(context: Context) {
        NotificationManagerCompat.from(context).cancel(CONFIRM_NOTIFICATION_ID)

        File(context.cacheDir, "update.apk").delete()
    }

    private fun abandon(context: Context, intent: Intent) {
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (session >= 0) {
            runCatching {
                context.packageManager.packageInstaller.abandonSession(session)
            }
        }
    }

    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "${context.packageName}.$ACTION_CONFIRM_DISMISSED") {
                abandon(context, intent)

                finish(context)

                UpdateTask.installFinished(UpdateTask.InstallOutcome.Returned)

                return
            }

            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }

                    if (confirm == null) {
                        abandon(context, intent)

                        finish(context)

                        UpdateTask.installFinished(UpdateTask.InstallOutcome.Returned)

                        return
                    }

                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    if (hasVisibleActivity()) {
                        context.startActivity(confirm)

                        return
                    }

                    if (notifyConfirm(context, confirm, intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))) return

                    Log.w("$TAG: подтверждение установки показать негде")

                    abandon(context, intent)

                    finish(context)

                    UpdateTask.installFinished(UpdateTask.InstallOutcome.Returned)
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i("$TAG: обновление установлено")
                    finish(context)

                    UpdateTask.installFinished(UpdateTask.InstallOutcome.Installed)
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    Log.i("$TAG: установка отменена")
                    finish(context)

                    UpdateTask.installFinished(UpdateTask.InstallOutcome.Returned)
                }

                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w("$TAG: установка не удалась, status=$status, $message")
                    finish(context)

                    UpdateTask.installFinished(UpdateTask.InstallOutcome.Refused(message))
                }
            }
        }
    }
}
