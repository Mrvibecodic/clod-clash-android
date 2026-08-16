package com.github.kr328.clash.update

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.store.AppStore
import java.util.concurrent.TimeUnit

object UpdatePrompt {
    private val CHECK_INTERVAL = TimeUnit.HOURS.toMillis(24)

    fun shouldCheckInBackground(context: Context): Boolean {
        val store = AppStore(context)

        if (!store.autoCheckUpdate) return false

        return System.currentTimeMillis() - store.lastUpdateCheck >= CHECK_INTERVAL
    }

    suspend fun check(context: Context, manual: Boolean, mixedPort: Int?): Updater.Available? {
        val store = AppStore(context)

        val available = runCatching {
            Updater.check(context, store.nightlyChannel, mixedPort)
        }.onFailure {
            Log.w("UpdatePrompt: проверка не удалась", it)
        }.getOrNull()

        store.lastUpdateCheck = System.currentTimeMillis()

        if (available == null) return null
        if (!manual && available.manifest.versionCode == store.skippedVersionCode) return null

        return available
    }

    fun skip(context: Context, versionCode: Long) {
        AppStore(context).skippedVersionCode = versionCode
    }
}
