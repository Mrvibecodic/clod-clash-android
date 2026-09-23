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

    sealed interface Outcome {
        data class Ready(val available: Updater.Available) : Outcome

        data object UpToDate : Outcome

        data class Failed(val kind: Updater.UpdateException.Kind?, val detail: String?) : Outcome
    }

    suspend fun check(context: Context, manual: Boolean, mixedPort: Int?): Outcome {
        val store = AppStore(context)

        val checked = try {
            Updater.check(context, store.prereleaseChannel, mixedPort)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        val result = checked.onFailure { Log.w("UpdatePrompt: проверка не удалась", it) }

        val kind = (result.exceptionOrNull() as? Updater.UpdateException)?.kind

        val unsupportedAbi = kind == Updater.UpdateException.Kind.NoBuild

        store.lastUpdateCheck = System.currentTimeMillis()
        store.lastUpdateCheckFailed = result.isFailure && !unsupportedAbi

        if (!manual && unsupportedAbi) return Outcome.UpToDate

        if (result.isFailure) return Outcome.Failed(kind, if (kind == null) result.exceptionOrNull()?.let { it.message ?: it.toString() } else null)

        val available = result.getOrNull() ?: return Outcome.UpToDate

        if (!manual && available.manifest.versionCode == store.skippedVersionCode) {
            return Outcome.UpToDate
        }

        return Outcome.Ready(available)
    }

    fun skip(context: Context, versionCode: Long) {
        AppStore(context).skippedVersionCode = versionCode
    }
}
