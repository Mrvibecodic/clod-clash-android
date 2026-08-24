package com.github.kr328.clash.util

import android.content.Context
import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.design.compose.screen.GeoFileState
import com.github.kr328.clash.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object GeoData {
    private const val TAG = "GeoData"

    private const val BASE =
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest"

    private val FILES = mapOf(
        "geoip.metadb" to "$BASE/geoip.metadb",
        "geosite.dat" to "$BASE/geosite.dat",
        "ASN.mmdb" to "$BASE/GeoLite2-ASN.mmdb",
    )

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 30_000
    private const val TOTAL_BUDGET = 180_000L
    private const val STALE_TEMP = 60L * 60 * 1000
    private const val BUFFER_SIZE = 64 * 1024
    private const val MIN_SIZE = 512L * 1024
    private const val SIZE_LIMIT = 64L * 1024 * 1024

    private val MMDB_MARKER = byteArrayOf(
        0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
    ) + "MaxMind.com".toByteArray()

    data class UpdateResult(
        val updated: List<String>,
        val failed: List<String>,
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

        runCatching {
            withContext(Dispatchers.IO) {
                val port = mixedPort?.takeIf { tunnelActive }
                val deadline = SystemClock.elapsedRealtime() + TOTAL_BUDGET
                val downloaded = mutableMapOf<String, File>()

                context.clashDir.mkdirs()

                sweep(context.clashDir)

                FILES.forEach { (name, url) ->
                    val temp = File(context.clashDir, "$name.download")

                    val received = SystemClock.elapsedRealtime() < deadline && (
                        if (port != null) {
                            download(url, port, temp, name) || download(url, null, temp, name)
                        } else {
                            download(url, null, temp, name)
                        }
                        )

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
                            if (temp.renameTo(File(context.clashDir, name))) {
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

            FILES.keys.forEach { name ->
                if (name !in updated && name !in failed) {
                    failed += name
                }
            }
        }

        return UpdateResult(updated = updated.toList(), failed = failed.toList())
    }

    private fun sweep(dir: File) {
        val now = System.currentTimeMillis()

        dir.listFiles()?.forEach {
            if (it.isFile && it.name.endsWith(".download") && now - it.lastModified() > STALE_TEMP) {
                it.delete()
            }
        }
    }

    private fun download(url: String, mixedPort: Int?, target: File, name: String): Boolean {
        return try {
            val proxy = mixedPort?.let {
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it))
            }
            val connection = (
                if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
                ) as HttpURLConnection

            connection.apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Updater.USER_AGENT)
            }

            try {
                if (connection.responseCode !in 200..299) {
                    Log.w("$TAG: $url -> ${connection.responseCode}")

                    return false
                }

                val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

                if (length !in MIN_SIZE..SIZE_LIMIT) {
                    Log.w("$TAG: $url -> declared $length bytes")

                    return false
                }

                var copied = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)

                        while (true) {
                            val read = input.read(buffer)

                            if (read < 0) {
                                break
                            }

                            copied += read

                            if (copied > length) {
                                Log.w("$TAG: $url is larger than declared")

                                return false
                            }

                            output.write(buffer, 0, read)
                        }
                    }
                }

                if (copied != length) {
                    Log.w("$TAG: $url -> $copied of $length bytes")

                    return false
                }

                verify(target, name)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w("$TAG: $url: $e", e)

            false
        }
    }

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
