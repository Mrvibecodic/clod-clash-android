package com.github.kr328.clash.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.net.Redirects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import com.github.kr328.clash.design.R as DesignR

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
    private const val MANIFEST_MAX_BYTES = 1L * 1024 * 1024
    private const val APK_MAX_BYTES = 200L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    class UpdateException(val kind: Kind, message: String) : IOException(message) {
        enum class Kind(@StringRes val text: Int) {
            Network(DesignR.string.clod_update_reason_network),
            Rejected(DesignR.string.clod_update_reason_rejected),
            NoBuild(DesignR.string.clod_update_reason_no_build),
        }
    }

    data class Available(
        val manifest: UpdateManifest,
        val platform: UpdateManifest.Platform,
    )

    suspend fun check(context: Context, prerelease: Boolean, mixedPort: Int?): Result<Available?> =
        withContext(Dispatchers.IO) {
            val release = load(MANIFEST_RELEASE, mixedPort)
            val current = currentVersionCode(context)
            val manifest = if (prerelease) {
                val preview = if (release.getOrNull()?.let { it.versionCode > current } == true) {
                    null
                } else {
                    load(MANIFEST_PRERELEASE, mixedPort).getOrNull()
                }

                listOfNotNull(release.getOrNull(), preview).maxByOrNull { it.versionCode }
                    ?: return@withContext Result.failure(
                        release.exceptionOrNull()
                            ?: UpdateException(UpdateException.Kind.Network, "манифест недоступен"),
                    )
            } else {
                release.getOrElse { return@withContext Result.failure(it) }
            }

            if (manifest.versionCode <= current) {
                return@withContext Result.success(null)
            }

            val platform = manifest.platformFor(Build.SUPPORTED_ABIS.toList())
            if (platform == null) {
                return@withContext Result.failure(
                    UpdateException(
                        UpdateException.Kind.NoBuild,
                        "в манифесте нет файла под ${Build.SUPPORTED_ABIS.joinToString()}",
                    ),
                )
            }

            if (!isHttps(platform.url)) {
                return@withContext Result.failure(
                    UpdateException(UpdateException.Kind.Rejected, "адрес обновления не https"),
                )
            }

            Result.success(Available(manifest, platform))
        }

    private fun isHttps(url: String): Boolean =
        runCatching { URL(url).protocol.equals("https", ignoreCase = true) }.getOrDefault(false)

    private suspend fun load(url: String, mixedPort: Int?): Result<UpdateManifest> {
        if (!isHttps(url)) {
            return Result.failure(UpdateException(UpdateException.Kind.Rejected, "адрес манифеста не https"))
        }

        val body = fetch(url, mixedPort)?.toString(Charsets.UTF_8)
            ?: return Result.failure(UpdateException(UpdateException.Kind.Network, "манифест недоступен"))

        return try {
            Result.success(json.decodeFromString(UpdateManifest.serializer(), body))
        } catch (e: Exception) {
            Log.w("$TAG: манифест не разобран", e)

            Result.failure(UpdateException(UpdateException.Kind.Rejected, "манифест не разобран"))
        }
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
                ?: throw UpdateException(UpdateException.Kind.Network, "не удалось скачать обновление")

            if (!actual.equals(available.platform.sha256, ignoreCase = true)) {
                throw UpdateException(
                    UpdateException.Kind.Rejected,
                    "контрольная сумма не совпала: ожидалась ${available.platform.sha256}, получена $actual",
                )
            }

            if (!hasSameSignature(context, target)) {
                throw UpdateException(
                    UpdateException.Kind.Rejected,
                    "файл подписан другим ключом — установка поверх невозможна",
                )
            }

            if (!matchesManifest(context, target, available.manifest)) {
                throw UpdateException(UpdateException.Kind.Rejected, "пакет или версия файла не совпадают с манифестом")
            }

            currentCoroutineContext().ensureActive()

            target
        }.onFailure {
            target.delete()

            if (it is CancellationException) throw it

            Log.w("$TAG: загрузка не удалась", it)
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

    private fun matchesManifest(context: Context, apk: File, manifest: UpdateManifest): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

        return info.packageName == context.packageName && versionCode == manifest.versionCode
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

    private class LimitExceededException(message: String) : IOException(message)

    private suspend fun <T : Any> request(
        url: String,
        mixedPort: Int?,
        read: suspend (HttpURLConnection) -> T,
    ): T? {
        for (proxy in routes(mixedPort)) {
            currentCoroutineContext().ensureActive()

            val result = runCatching {
                val connection = Redirects.open(
                    url,
                    { address -> address.openConnection(proxy) as HttpURLConnection },
                    { open ->
                        open.connectTimeout = CONNECT_TIMEOUT
                        open.readTimeout = READ_TIMEOUT
                        open.setRequestProperty("User-Agent", USER_AGENT)
                    },
                )

                try {
                    if (connection.responseCode !in 200..299) {
                        error("HTTP ${connection.responseCode}")
                    }

                    read(connection)
                } finally {
                    connection.disconnect()
                }
            }.onFailure {
                if (it is CancellationException) throw it

                Log.i("$TAG: $url через $proxy не удалось: ${it.message}")
            }

            if (result.exceptionOrNull() is LimitExceededException) return null

            result.getOrNull()?.let { return it }
        }

        return null
    }

    private suspend fun fetch(url: String, mixedPort: Int?): ByteArray? =
        request(url, mixedPort) { connection ->
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

            if (total > MANIFEST_MAX_BYTES) {
                throw LimitExceededException("манифест больше $MANIFEST_MAX_BYTES байт")
            }

            connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)

                while (true) {
                    currentCoroutineContext().ensureActive()

                    val read = input.read(buffer)
                    if (read < 0) break

                    if (bytes.size() + read > MANIFEST_MAX_BYTES) {
                        throw LimitExceededException("манифест больше $MANIFEST_MAX_BYTES байт")
                    }

                    bytes.write(buffer, 0, read)
                }

                bytes.toByteArray()
            }
        }

    private suspend fun downloadTo(
        url: String,
        mixedPort: Int?,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): String? = request(url, mixedPort) { connection ->
        val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

        if (total > APK_MAX_BYTES) {
            throw LimitExceededException("файл больше $APK_MAX_BYTES байт")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var received = 0L
        var lastReported = 0L

        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()

                    val read = input.read(buffer)
                    if (read < 0) break

                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)

                    received += read

                    if (received > APK_MAX_BYTES) {
                        throw LimitExceededException("файл больше $APK_MAX_BYTES байт")
                    }

                    if (received - lastReported >= PROGRESS_STEP) {
                        lastReported = received

                        onProgress(received, total)
                    }
                }

                output.flush()
            }
        }

        if (total > 0 && received != total) {
            error("получено $received байт из $total")
        }

        onProgress(received, total)

        digest.digest().joinToString("") { "%02x".format(it) }
    }

    val USER_AGENT: String = "ClodClash/" + BuildConfig.VERSION_NAME + " (Android)"
}
