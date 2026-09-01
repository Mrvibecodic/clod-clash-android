package com.github.kr328.clash.remote

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider

class StatusClient(private val context: Context) {
    private val uri: Uri
        get() {
            return Uri.Builder()
                .scheme("content")
                .authority(Authorities.STATUS_PROVIDER)
                .build()
        }

    data class Status(
        val running: Boolean,
        val name: String?,
        val starting: Boolean = false,
        val stage: String? = null,
    )

    fun status(): Status {
        return try {
            val result = context.contentResolver.call(
                uri,
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            ) ?: return Status(running = false, name = null)

            Status(
                running = result.getBoolean(StatusProvider.KEY_RUNNING),
                name = result.getString(StatusProvider.KEY_NAME),
                starting = result.getBoolean(StatusProvider.KEY_STARTING),
                stage = result.getString(StatusProvider.KEY_STAGE),
            )
        } catch (e: Exception) {
            Log.w("Query clash status: $e", e)

            Status(running = false, name = null)
        }
    }

    fun isRunning(): Boolean = status().running

    fun isActive(): Boolean = status().let { it.running || it.starting }
}
