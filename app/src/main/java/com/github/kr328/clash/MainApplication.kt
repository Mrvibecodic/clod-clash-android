package com.github.kr328.clash

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
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
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName

        GeoAssets.extract(this)

        restoreLauncherIconOnTelevision()

        Log.d("Process $processName started")

        if (processName == packageName) {
            Remote.launch()
        } else {
            sendServiceRecreated()
        }
    }

    private fun restoreLauncherIconOnTelevision() {
        val uiStore = UiStore(this)

        if (!uiStore.hideAppIcon || !isTelevision()) return

        uiStore.hideAppIcon = false

        packageManager.setComponentEnabledSetting(
            mainActivityAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun finalize() {
        Global.destroy()
    }
}
