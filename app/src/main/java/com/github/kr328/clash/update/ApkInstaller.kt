package com.github.kr328.clash.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.github.kr328.clash.common.log.Log
import java.io.File

/**
 * Установка APK средствами системы.
 *
 * Используется PackageInstaller Session API, а не Intent.ACTION_INSTALL_PACKAGE:
 * последний помечен deprecated, не требует FileProvider и, главное, не сообщает
 * причину отказа — при разной подписи или откате версии пользователь просто видит
 * «приложение не установлено» без объяснений.
 *
 * Системный экран подтверждения показывается всегда и убрать его нельзя — это
 * нормальное поведение, а не недоработка.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"

    const val ACTION_INSTALL_STATUS = "install_status"

    /** С Android 8 разрешение выдаётся не глобально, а конкретному приложению. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Открывает системный экран, где это разрешение выдают. */
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

    /**
     * Ставит [apk] поверх текущего приложения.
     *
     * Условия, без которых установка не пройдёт (проверяются раньше, в [Updater]):
     * тот же applicationId, та же подпись, versionCode строго больше установленного.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14: закрепляем обновления за собой. Иначе каждое следующее
                // обновление не от «владельца» показывает лишний предупреждающий экран.
                setRequestUpdateOwnership(true)
            }
        }

        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                // Без fsync данные могут не долететь до сессии, и commit упадёт
                // с невнятной ошибкой чтения.
                session.fsync(output)
            }

            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Именно MUTABLE: система дописывает в этот intent свой EXTRA_STATUS.
                flags = flags or PendingIntent.FLAG_MUTABLE
            }

            val status = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent("${context.packageName}.$ACTION_INSTALL_STATUS")
                    .setPackage(context.packageName),
                flags,
            )

            session.commit(status.intentSender)
        }
    }

    /**
     * Приёмник результата установки.
     *
     * Ключевой случай — STATUS_PENDING_USER_ACTION: система просит показать свой
     * диалог подтверждения. Если его не показать, установка молча зависает —
     * это самая частая ошибка в таком коде.
     */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }

                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    confirm?.let(context::startActivity)
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i("$TAG: обновление установлено")
                    File(context.cacheDir, "update.apk").delete()
                }

                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w("$TAG: установка не удалась, status=$status, $message")
                }
            }
        }
    }
}
