package com.github.kr328.clash.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.compat.versionCodeCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.UpdateFailures
import com.github.kr328.clash.util.Transfer
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
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.R as ServiceR

object Updater {
    private const val TAG = "Updater"

    fun apkFile(context: Context): File = File(context.cacheDir, "update.apk")

    private const val MANIFEST_RELEASE =
        "https://github.com/Mrvibecodic/clod-clash-android/releases/download/updater/latest.json"
    private const val MANIFEST_PRERELEASE =
        "https://github.com/Mrvibecodic/clod-clash-android/releases/download/updater-prerelease/latest.json"

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val PROGRESS_STEP = 256 * 1024
    private const val MANIFEST_MAX_BYTES = 1L * 1024 * 1024
    private const val APK_MAX_BYTES = 200L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    class UpdateException(val kind: Kind, message: String) : IOException(message) {
        enum class Kind(@StringRes val text: Int) {
            Network(DesignR.string.clod_update_reason_network),
            Rejected(DesignR.string.clod_update_reason_rejected),
            NoBuild(DesignR.string.clod_update_reason_no_build),
            Storage(ServiceR.string.clod_update_cause_storage),
        }
    }

    data class Available(
        val manifest: UpdateManifest,
        val platform: UpdateManifest.Platform,
    )

    // Любой сбой проверки — неудача, не исключение: вызывающие (экран и фоновая
    // проверка) разбирают только Result
    suspend fun check(context: Context, prerelease: Boolean, mixedPort: Int?): Result<Available?> =
        withContext(Dispatchers.IO) {
            try {
                checkManifest(context, prerelease, mixedPort)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun checkManifest(context: Context, prerelease: Boolean, mixedPort: Int?): Result<Available?> {
        // Канал пре-релизов — только свой манифест: релиз кладёт туда любую свежую
        // сборку, обычную тоже. Запасной обычный манифест при сбое этого подсунул бы
        // старую версию — «обновлений нет» вместо ошибки.
        val manifest = load(if (prerelease) MANIFEST_PRERELEASE else MANIFEST_RELEASE, mixedPort)
            .getOrElse { return Result.failure(it) }
        val current = currentVersionCode(context)

        if (manifest.versionCode <= current) {
            return Result.success(null)
        }

        val platform = manifest.platformFor(Build.SUPPORTED_ABIS.toList())
            ?: return Result.failure(
                UpdateException(
                    UpdateException.Kind.NoBuild,
                    "в манифесте нет файла под ${Build.SUPPORTED_ABIS.joinToString()}",
                ),
            )

        if (!isHttps(platform.url)) {
            return Result.failure(UpdateException(UpdateException.Kind.Rejected, "адрес обновления не https"))
        }

        return Result.success(Available(manifest, platform))
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
        onProgress: (received: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = apkFile(context)

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

    fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).versionCodeCompat

    private fun matchesManifest(context: Context, apk: File, manifest: UpdateManifest): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false

        return info.packageName == context.packageName && info.versionCodeCompat == manifest.versionCode
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
            add(Transfer.localProxy(mixedPort))
        }
    }

    private suspend fun <T : Any> request(
        url: String,
        mixedPort: Int?,
        read: suspend (HttpURLConnection) -> T?,
    ): T? = Transfer.firstRoute(TAG, url, routes(mixedPort), CONNECT_TIMEOUT, READ_TIMEOUT, stop = { it is UpdateException }, read = read)

    private suspend fun fetch(url: String, mixedPort: Int?): ByteArray? =
        request(url, mixedPort) { connection ->
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

            if (total > MANIFEST_MAX_BYTES) {
                throw Transfer.TooLarge("манифест больше $MANIFEST_MAX_BYTES байт")
            }

            connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()

                Transfer.copy(input, bytes, MANIFEST_MAX_BYTES)

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
            throw Transfer.TooLarge("файл больше $APK_MAX_BYTES байт")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var lastReported = 0L

        val received = connection.inputStream.use { input ->
            try {
                target.outputStream().use { output ->
                    Transfer.copy(input, output, APK_MAX_BYTES) { buffer, read, copied ->
                        digest.update(buffer, 0, read)

                        if (copied - lastReported >= PROGRESS_STEP) {
                            lastReported = copied

                            onProgress(copied, total)
                        }
                    }.also { output.flush() }
                }
            } catch (e: IOException) {
                throw if (UpdateFailures.noSpace(e)) UpdateException(UpdateException.Kind.Storage, e.message.orEmpty()) else e
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
