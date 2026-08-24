package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.design.compose.screen.GeoFileState
import com.github.kr328.clash.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 60_000

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

    suspend fun update(context: Context, mixedPort: Int?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                GeoAssets.awaitReady(context)

                context.clashDir.mkdirs()

                FILES.forEach { (name, url) ->
                    val target = File(context.clashDir, name)
                    val temp = File(context.clashDir, "$name.download")

                    try {
                        val bytes = fetch(url, null)
                            ?: fetch(url, mixedPort)
                            ?: error("не удалось скачать $name")

                        temp.writeBytes(bytes)

                        if (!temp.renameTo(target)) {
                            temp.copyTo(target, overwrite = true)
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }
        }

    private fun fetch(url: String, mixedPort: Int?): ByteArray? {
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

                    return null
                }

                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w("$TAG: $url: $e", e)

            null
        }
    }
}
