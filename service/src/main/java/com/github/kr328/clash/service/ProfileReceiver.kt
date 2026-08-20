package com.github.kr328.clash.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class ProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                val pending = goAsync()

                Global.launch {
                    try {
                        reset()

                        val service = Intent(Intents.ACTION_PROFILE_SCHEDULE_UPDATES)
                            .setComponent(ProfileWorker::class.componentName)

                        context.startForegroundServiceCompat(service)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Reschedule updates on ${intent.action}: $e", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                val redirect = intent.setComponent(ProfileWorker::class.componentName)

                try {
                    context.startForegroundServiceCompat(redirect)
                } catch (e: Exception) {
                    Log.w("Start profile update: $e", e)

                    val uuid = redirect.uuid ?: return
                    val pending = goAsync()

                    Global.launch {
                        try {
                            ImportedDao().queryByUUID(uuid)
                                ?.let { scheduleRetry(context, it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("Reschedule failed update: $e", e)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val lock = Mutex()
        private var initialized: Boolean = false

        suspend fun rescheduleAll(context: Context) = lock.withLock {
            if (initialized)
                return

            initialized = true

            Log.i("Reschedule all profiles update")

            ImportedDao().queryAllUUIDs()
                .mapNotNull { ImportedDao().queryByUUID(it) }
                .filter { it.type != Profile.Type.File }
                .forEach { scheduleNext(context, it) }
        }

        fun cancelNext(context: Context, imported: Imported) {
            cancelAlarm(context, imported)
        }

        fun schedule(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            cancelAlarm(context, imported)

            intent.send(context, 0, null)
        }

        fun scheduleNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            cancelAlarm(context, imported)

            if (imported.interval < TimeUnit.MINUTES.toMillis(15))
                return

            val current = System.currentTimeMillis()
            val last = context.importedDir
                .resolve(imported.uuid.toString())
                .resolve("config.yaml")
                .lastModified()

            if (last < 0)
                return

            val interval = (imported.interval - (current - last)).coerceAtLeast(0)

            setAlarm(context, current + interval, intent)
        }

        fun scheduleRetry(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            cancelAlarm(context, imported)

            if (imported.interval < TimeUnit.MINUTES.toMillis(15))
                return

            setAlarm(
                context,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15),
                intent
            )
        }

        private fun setAlarm(context: Context, timestamp: Long, intent: PendingIntent) {
            val manager = context.getSystemService<AlarmManager>() ?: return

            val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()

            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, intent)
            }
        }

        private fun cancelAlarm(context: Context, imported: Imported) {
            val manager = context.getSystemService<AlarmManager>() ?: return

            manager.cancel(pendingIntentOf(context, imported))

            legacyPendingIntentOf(context, imported)?.let {
                manager.cancel(it)
                it.cancel()
            }
        }

        private suspend fun reset() = lock.withLock {
            initialized = false
        }

        private fun pendingIntentOf(context: Context, imported: Imported): PendingIntent {
            val intent = Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                .setComponent(ProfileReceiver::class.componentName)
                .setUUID(imported.uuid)

            return PendingIntent.getBroadcast(
                context,
                imported.uuid.hashCode(),
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }

        private fun legacyPendingIntentOf(context: Context, imported: Imported): PendingIntent? {
            val intent = Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                .setComponent(ProfileReceiver::class.componentName)
                .setUUID(imported.uuid)

            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_NO_CREATE)
            )
        }
    }
}
