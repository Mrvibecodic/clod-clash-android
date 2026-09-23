package com.github.kr328.clash

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Configuration
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.compat.isTelevision
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias

@Suppress("unused")
class MainApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setDefaultProcessName("$packageName:background")
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
}
