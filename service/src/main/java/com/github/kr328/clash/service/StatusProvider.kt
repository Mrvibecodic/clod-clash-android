package com.github.kr328.clash.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.ServiceStore
import java.io.IOException

class StatusProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_CURRENT_PROFILE -> {
                // Отметка старта сессии — для таймера главной; без неё ответ о
                // состоянии всё равно нужен (плитка, виджет, сторож)
                val session = runCatching {
                    ServiceStore(context!!).run { clashStartedAt to clashStartedElapsed }
                }.getOrElse {
                    Log.w("Status: session start unreadable: $it", it)

                    0L to 0L
                }

                return Bundle().apply {
                    putBoolean(KEY_RUNNING, serviceReady)
                    putBoolean(KEY_STARTING, serviceRunning && !serviceReady)
                    putString(KEY_STAGE, startupStage)
                    putString(KEY_NAME, currentProfile)
                    putString(KEY_UUID, currentProfileUuid)
                    putBoolean(KEY_RESTARTED, serviceReady && restartedBySystem)
                    putBoolean(KEY_PROXY_REFUSED, serviceReady && systemProxyRefused)
                    putLong(KEY_STARTED_AT, session.first)
                    putLong(KEY_STARTED_ELAPSED, session.second)
                }
            }
            METHOD_UPDATING_PROFILES -> {
                return Bundle().apply {
                    putStringArrayList(KEY_UPDATING, ArrayList(ProfileUpdateWorker.updating.map { it.toString() }))
                }
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw IllegalArgumentException("Stub!")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        throw IllegalArgumentException("Stub!")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw IllegalArgumentException("Stub!")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw IllegalArgumentException("Stub!")
    }

    override fun getType(uri: Uri): String? {
        throw IllegalArgumentException("Stub!")
    }

    override fun onCreate(): Boolean {
        return true
    }

    companion object {
        const val METHOD_CURRENT_PROFILE = "currentProfile"
        const val KEY_RUNNING = "running"
        const val KEY_STARTING = "starting"
        const val KEY_STAGE = "stage"
        const val KEY_NAME = "name"
        const val KEY_UUID = "uuid"
        const val KEY_RESTARTED = "restarted"
        const val KEY_PROXY_REFUSED = "proxyRefused"
        const val KEY_STARTED_AT = "startedAt"
        const val KEY_STARTED_ELAPSED = "startedElapsed"
        const val METHOD_UPDATING_PROFILES = "updatingProfiles"
        const val KEY_UPDATING = "updating"

        private const val CLASH_SERVICE_RUNNING_FILE = "service_running.lock"

        @Volatile
        var serviceRunning: Boolean = false
            set(value) {
                field = value

                shouldStartClashOnBoot = value
            }
        @Volatile
        var serviceReady: Boolean = false

        @Volatile
        var startupStage: String? = null

        var shouldStartClashOnBoot: Boolean
            get() = Global.application.filesDir.resolve(CLASH_SERVICE_RUNNING_FILE).exists()
            set(value) {
                try {
                    Global.application.filesDir.resolve(CLASH_SERVICE_RUNNING_FILE).apply {
                        if (value)
                            createNewFile()
                        else
                            delete()
                    }
                } catch (e: IOException) {
                    Log.w("Update $CLASH_SERVICE_RUNNING_FILE failed", e)
                }
            }
        var currentProfile: String? = null

        @Volatile
        var currentProfileUuid: String? = null

        @Volatile
        var restartedBySystem: Boolean = false

        @Volatile
        var systemProxyRefused: Boolean = false
    }
}
