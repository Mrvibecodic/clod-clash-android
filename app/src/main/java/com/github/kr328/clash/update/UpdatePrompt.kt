package com.github.kr328.clash.update

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.store.AppStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object UpdatePrompt {
    private val CHECK_INTERVAL = TimeUnit.HOURS.toMillis(24)
    private val RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(30)

    fun shouldCheckInBackground(context: Context): Boolean {
        val store = AppStore(context)

        if (!store.autoCheckUpdate) return false

        val interval = if (store.lastUpdateCheckFailed) RETRY_INTERVAL else CHECK_INTERVAL

        return System.currentTimeMillis() - store.lastUpdateCheck >= interval
    }

    suspend fun check(context: Context, manual: Boolean, mixedPort: Int?): Updater.Available? {
        val store = AppStore(context)

        val checked = try {
            Updater.check(context, store.nightlyChannel, mixedPort)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        val result = checked.onFailure { Log.w("UpdatePrompt: проверка не удалась", it) }

        store.lastUpdateCheck = System.currentTimeMillis()
        store.lastUpdateCheckFailed = result.isFailure

        val available = result.getOrNull() ?: return null

        if (!manual && available.manifest.versionCode == store.skippedVersionCode) return null

        return available
    }

    fun skip(context: Context, versionCode: Long) {
        AppStore(context).skippedVersionCode = versionCode
    }
}
