package com.github.kr328.clash.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import java.io.IOException

class StatusProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_CURRENT_PROFILE -> {
                return Bundle().apply {
                    putBoolean(KEY_RUNNING, serviceReady)
                    putBoolean(KEY_STARTING, serviceRunning && !serviceReady)
                    putString(KEY_STAGE, startupStage)
                    putString(KEY_NAME, currentProfile)
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
    }
}
