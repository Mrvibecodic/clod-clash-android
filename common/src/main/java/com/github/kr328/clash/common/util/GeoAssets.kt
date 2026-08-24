package com.github.kr328.clash.common.util

import android.content.Context
import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicBoolean

object GeoAssets {
    private const val TAG = "GeoAssets"

    private const val LOCK_TIMEOUT = 15_000L
    private const val LOCK_INTERVAL = 100L
    private const val READY_TIMEOUT = 60_000L

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

        try {
            val application = context.applicationContext

            scope.launch {
                try {
                    guarded(application) { extractAll(application) }
                } catch (e: Throwable) {
                    Log.w("$TAG: $e", e)
                } finally {
                    ready.complete(Unit)
                }
            }
        } catch (e: Throwable) {
            Log.w("$TAG: $e", e)

            ready.complete(Unit)
        }
    }

    suspend fun awaitReady(context: Context) {
        extract(context)

        if (withTimeoutOrNull(READY_TIMEOUT) { ready.await() } == null) {
            Log.w("$TAG: waiting for assets timed out")
        }
    }

    suspend fun <T> writeGuarded(context: Context, block: () -> T): T = withContext(Dispatchers.IO) {
        awaitReady(context)

        guarded(context.applicationContext, block)
    }

    private fun <T> guarded(context: Context, block: () -> T): T {
        val handle = try {
            RandomAccessFile(File(context.filesDir, "geo.lock"), "rw")
        } catch (e: Throwable) {
            Log.w("$TAG: $e", e)

            null
        } ?: return block()

        return handle.use { file ->
            val lock = acquire(file)

            try {
                block()
            } finally {
                try {
                    lock?.release()
                } catch (e: Throwable) {
                    Log.w("$TAG: $e", e)
                }
            }
        }
    }

    private fun acquire(file: RandomAccessFile): FileLock? {
        val deadline = SystemClock.elapsedRealtime() + LOCK_TIMEOUT

        while (true) {
            val lock = try {
                file.channel.tryLock()
            } catch (e: Throwable) {
                Log.w("$TAG: $e", e)

                return null
            }

            if (lock != null) {
                return lock
            }

            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w("$TAG: lock is busy")

                return null
            }

            try {
                Thread.sleep(LOCK_INTERVAL)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()

                return null
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
            } catch (e: Throwable) {
                Log.w("$TAG: $name: $e", e)
            } finally {
                temp.delete()
            }
        }
    }
}
