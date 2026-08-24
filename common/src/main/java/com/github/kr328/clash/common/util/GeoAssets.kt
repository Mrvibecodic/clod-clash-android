package com.github.kr328.clash.common.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

object GeoAssets {
    private const val TAG = "GeoAssets"

    private val names = listOf(
        "geoip.metadb",
        "geosite.dat",
        "ASN.mmdb",
        "BundleMRS.7z",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()

    fun extract(context: Context) {
        if (!started.compareAndSet(false, true)) {
            return
        }

        val application = context.applicationContext

        scope.launch {
            try {
                extractLocked(application)
            } catch (e: Exception) {
                Log.w("$TAG: $e", e)
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun awaitReady(context: Context) {
        extract(context)

        ready.await()
    }

    private fun extractLocked(context: Context) {
        val handle = try {
            RandomAccessFile(File(context.filesDir, "geo.lock"), "rw")
        } catch (e: Exception) {
            Log.w("$TAG: $e", e)

            null
        }

        if (handle == null) {
            extractAll(context)

            return
        }

        handle.use { file ->
            val lock = try {
                file.channel.lock()
            } catch (e: Exception) {
                Log.w("$TAG: $e", e)

                null
            }

            try {
                extractAll(context)
            } finally {
                try {
                    lock?.release()
                } catch (e: Exception) {
                    Log.w("$TAG: $e", e)
                }
            }
        }
    }

    private fun extractAll(context: Context) {
        val dir = File(context.filesDir, "clash")

        dir.mkdirs()

        val installedAt = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .lastUpdateTime

        names.forEach { name ->
            val target = File(dir, name)

            if (target.isFile && target.length() > 0 && target.lastModified() >= installedAt) {
                return@forEach
            }

            val temp = File(dir, "$name.extracting")

            try {
                FileOutputStream(temp).use { output ->
                    context.assets.open(name).use { it.copyTo(output) }
                }

                if (!temp.renameTo(target)) {
                    Log.w("$TAG: unable to replace $name")
                }
            } catch (e: Exception) {
                Log.w("$TAG: $name: $e", e)
            } finally {
                temp.delete()
            }
        }
    }
}
