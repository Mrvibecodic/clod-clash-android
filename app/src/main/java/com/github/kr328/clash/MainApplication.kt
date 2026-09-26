package com.github.kr328.clash

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.Configuration
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.compat.isTelevision
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias

@Suppress("unused")
class MainApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setDefaultProcessName("$packageName:background")
            // На каждую подписку две задачи (периодическая и после истечения
            // срока); умолчание WorkManager — 20 слотов JobScheduler на всё
            // приложение, и с десятком подписок периодические ждали бы, пока
            // долгие задачи истечения освободят место.
            .setMaxSchedulerLimit(50)
            .build()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName

        GeoAssets.extract(this)

        Log.i("Process $processName started")

        if (processName == packageName) {
            UiStore(this).apply {
                dropRemovedKeys()
                darkMode.applyToSystem(this@MainApplication)
            }
            restoreLauncherIconIfUnsupported()

            NotificationManagerCompat.from(this).deleteNotificationChannel(LEGACY_WIDGET_CHANNEL)
            clashDir.resolve(LEGACY_CHAN_SKEW).delete()

            Remote.launch()
        } else {
            sendServiceRecreated()

            ToggleWidgetProvider.renderRecreated(this)
        }
    }

    private fun restoreLauncherIconIfUnsupported() {
        val uiStore = UiStore(this)

        if (!uiStore.hideAppIcon) return
        if (!isTelevision() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return

        uiStore.hideAppIcon = false

        packageManager.setComponentEnabledSetting(
            mainActivityAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private companion object {
        const val LEGACY_WIDGET_CHANNEL = "widget_permission_channel"
        const val LEGACY_CHAN_SKEW = "chan.skew"
    }
}
