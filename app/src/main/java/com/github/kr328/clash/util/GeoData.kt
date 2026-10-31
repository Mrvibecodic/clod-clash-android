package com.github.kr328.clash.util

import android.content.Context
import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.common.util.clashDir
import com.github.kr328.clash.common.util.moveDurablyTo
import com.github.kr328.clash.design.compose.screen.GeoFileState
import com.github.kr328.clash.service.util.UpdateFailures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Proxy

object GeoData {
    private const val TAG = "GeoData"

    private val FILES = GeoAssets.files.mapNotNull { entry -> entry.update?.let { entry.name to it } }.toMap()

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 30_000
    private const val TOTAL_BUDGET = 180_000L
    private const val STALE_TEMP = 60L * 60 * 1000
    private const val MIN_SIZE = 512L * 1024
    private const val SIZE_LIMIT = 64L * 1024 * 1024

    private val MMDB_MARKER = byteArrayOf(
        0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
    ) + "MaxMind.com".toByteArray()

    data class UpdateResult(
        val updated: List<String>,
        val failed: List<String>,
        val noSpace: Boolean = false,
    )

    suspend fun query(context: Context): List<GeoFileState> = withContext(Dispatchers.IO) {
        GeoAssets.awaitReady(context)

        FILES.keys.map { name ->
            val file = File(context.clashDir, name)

            GeoFileState(
                name = name,
                sizeBytes = if (file.isFile) file.length() else 0,
                updatedAt = if (file.isFile) file.lastModified() else 0,
            )
        }
    }

    suspend fun update(
        context: Context,
        mixedPort: Int?,
        tunnelActive: Boolean,
    ): UpdateResult {
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var noSpace = false

        runCatching {
            withContext(Dispatchers.IO) {
                val port = mixedPort?.takeIf { tunnelActive }
                val deadline = SystemClock.elapsedRealtime() + TOTAL_BUDGET
                val downloaded = mutableMapOf<String, File>()

                context.clashDir.mkdirs()

                sweep(context.clashDir)

                FILES.forEach { (name, url) ->
                    val temp = File(context.clashDir, "$name.download")

                    val received = SystemClock.elapsedRealtime() < deadline &&
                        download(url, listOfNotNull(port?.let(Transfer::localProxy)) + null, temp, name) { noSpace = true }

                    if (received) {
                        downloaded[name] = temp
                    } else {
                        temp.delete()

                        failed += name
                    }
                }

                if (downloaded.isNotEmpty()) {
                    GeoAssets.writeGuarded(context) {
                        downloaded.forEach { (name, temp) ->
                            if (temp.moveDurablyTo(File(context.clashDir, name))) {
                                updated += name
                            } else {
                                Log.w("$TAG: unable to replace $name")

                                temp.delete()

                                failed += name
                            }
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.w("$TAG: $error", error)

            if (UpdateFailures.noSpace(error)) noSpace = true

            FILES.keys.forEach { name ->
                if (name !in updated && name !in failed) {
                    failed += name
                }
            }
        }

        return UpdateResult(updated = updated.toList(), failed = failed.toList(), noSpace = noSpace)
    }

    private fun sweep(dir: File) {
        val now = System.currentTimeMillis()

        dir.listFiles()?.forEach {
            if (it.isFile && it.name.endsWith(".download") && now - it.lastModified() > STALE_TEMP) {
                it.delete()
            }
        }
    }

    private suspend fun download(url: String, routes: List<Proxy?>, target: File, name: String, onFull: () -> Unit): Boolean =
        Transfer.firstRoute(TAG, url, routes, CONNECT_TIMEOUT, READ_TIMEOUT) { connection ->
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

            if (length !in MIN_SIZE..SIZE_LIMIT) {
                Log.w("$TAG: $url -> declared $length bytes")

                return@firstRoute null
            }

            val copied = try {
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output -> Transfer.copy(input, output, length) }
                }
            } catch (e: Transfer.TooLarge) {
                Log.w("$TAG: $url is larger than declared")

                return@firstRoute null
            } catch (e: IOException) {
                if (UpdateFailures.noSpace(e)) onFull()

                throw e
            }

            if (copied != length) {
                Log.w("$TAG: $url -> $copied of $length bytes")

                return@firstRoute null
            }

            verify(target, name).takeIf { it }
        } == true

    private fun verify(file: File, name: String): Boolean {
        if (!name.endsWith(".mmdb") && !name.endsWith(".metadb")) {
            return true
        }

        return try {
            val size = file.length()
            val window = minOf(size, 4096L)
            val tail = ByteArray(window.toInt())

            RandomAccessFile(file, "r").use {
                it.seek(size - window)
                it.readFully(tail)
            }

            val found = (0..tail.size - MMDB_MARKER.size).any { offset ->
                MMDB_MARKER.indices.all { tail[offset + it] == MMDB_MARKER[it] }
            }

            if (!found) {
                Log.w("$TAG: $name is not a database")
            }

            found
        } catch (e: Exception) {
            Log.w("$TAG: $name: $e", e)

            false
        }
    }
}
