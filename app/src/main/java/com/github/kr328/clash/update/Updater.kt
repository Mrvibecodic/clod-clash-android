package com.github.kr328.clash.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

object Updater {
    private const val TAG = "Updater"

    private const val MANIFEST_RELEASE =
        "https://github.com/Mrvibecodic/clod-clash-android/releases/download/updater/latest.json"
    private const val MANIFEST_PRERELEASE =
        "https://github.com/Mrvibecodic/clod-clash-android/releases/download/updater-prerelease/latest.json"

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_STEP = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    data class Available(
        val manifest: UpdateManifest,
        val platform: UpdateManifest.Platform,
    )

    suspend fun check(context: Context, prerelease: Boolean, mixedPort: Int?): Result<Available?> =
        withContext(Dispatchers.IO) {
            val release = load(MANIFEST_RELEASE, mixedPort)
            val manifest = if (prerelease) {
                val preview = load(MANIFEST_PRERELEASE, mixedPort).getOrNull()

                listOfNotNull(release.getOrNull(), preview).maxByOrNull { it.versionCode }
                    ?: return@withContext Result.failure(
                        release.exceptionOrNull() ?: IOException("манифест недоступен"),
                    )
            } else {
                release.getOrElse { return@withContext Result.failure(it) }
            }

            if (manifest.versionCode <= currentVersionCode(context)) {
                return@withContext Result.success(null)
            }

            val platform = manifest.platformFor(Build.SUPPORTED_ABIS.toList())
            if (platform == null) {
                Log.w("$TAG: в манифесте нет файла под ${Build.SUPPORTED_ABIS.joinToString()}")
                return@withContext Result.success(null)
            }

            Result.success(Available(manifest, platform))
        }

    private fun load(url: String, mixedPort: Int?): Result<UpdateManifest> {
        val body = fetch(url, mixedPort)?.toString(Charsets.UTF_8)
            ?: return Result.failure(IOException("манифест недоступен"))

        return runCatching { json.decodeFromString(UpdateManifest.serializer(), body) }
            .onFailure { Log.w("$TAG: манифест не разобран", it) }
    }

    suspend fun download(
        context: Context,
        available: Available,
        mixedPort: Int?,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "update.apk")

        runCatching {
            target.delete()

            val actual = downloadTo(available.platform.url, mixedPort, target, onProgress)
                ?: error("не удалось скачать обновление")

            if (!actual.equals(available.platform.sha256, ignoreCase = true)) {
                error("контрольная сумма не совпала: ожидалась ${available.platform.sha256}, получена $actual")
            }

            if (!hasSameSignature(context, target)) {
                error("файл подписан другим ключом — установка поверх невозможна")
            }

            target
        }.onFailure {
            Log.w("$TAG: загрузка не удалась", it)

            target.delete()
        }
    }

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun hasSameSignature(context: Context, apk: File): Boolean {
        val pm = context.packageManager

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flag = PackageManager.GET_SIGNING_CERTIFICATES
                val installed = pm.getPackageInfo(context.packageName, flag).signingInfo
                val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flag)?.signingInfo

                val a = installed?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
                val b = candidate?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()

                a.isNotEmpty() && a == b
            } else {
                @Suppress("DEPRECATION")
                val flag = PackageManager.GET_SIGNATURES
                @Suppress("DEPRECATION")
                val a = pm.getPackageInfo(context.packageName, flag).signatures
                    ?.map { it.toCharsString() }?.toSet().orEmpty()
                @Suppress("DEPRECATION")
                val b = pm.getPackageArchiveInfo(apk.absolutePath, flag)?.signatures
                    ?.map { it.toCharsString() }?.toSet().orEmpty()

                a.isNotEmpty() && a == b
            }
        }.getOrElse {
            Log.w("$TAG: подпись не проверена", it)
            false
        }
    }

    private fun routes(mixedPort: Int?): List<Proxy> = buildList {
        add(Proxy.NO_PROXY)

        if (mixedPort != null && mixedPort > 0) {
            add(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort)))
        }
    }

    private fun <T : Any> request(
        url: String,
        mixedPort: Int?,
        read: (HttpURLConnection) -> T,
    ): T? {
        for (proxy in routes(mixedPort)) {
            val result = runCatching {
                val connection = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }

                try {
                    if (connection.responseCode !in 200..299) {
                        error("HTTP ${connection.responseCode}")
                    }

                    read(connection)
                } finally {
                    connection.disconnect()
                }
            }.onFailure {
                Log.d("$TAG: $url через $proxy не удалось: ${it.message}")
            }.getOrNull()

            if (result != null) return result
        }

        return null
    }

    private fun fetch(url: String, mixedPort: Int?): ByteArray? =
        request(url, mixedPort) { connection -> connection.inputStream.use { it.readBytes() } }

    private fun downloadTo(
        url: String,
        mixedPort: Int?,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): String? = request(url, mixedPort) { connection ->
        val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var received = 0L
        var lastReported = 0L

        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break

                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)

                    received += read

                    if (received - lastReported >= PROGRESS_STEP) {
                        lastReported = received

                        onProgress(received, total)
                    }
                }

                output.flush()
            }
        }

        onProgress(received, total)

        digest.digest().joinToString("") { "%02x".format(it) }
    }

    val USER_AGENT: String = "ClodClash/" + BuildConfig.VERSION_NAME + " (Android)"
}
