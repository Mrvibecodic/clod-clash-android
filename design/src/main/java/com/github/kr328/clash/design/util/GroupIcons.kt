package com.github.kr328.clash.design.util

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.github.kr328.clash.common.log.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object GroupIcons {
    private const val DIRECTORY = "group-icons"
    private const val MAX_BYTES = 512 * 1024
    private const val TIMEOUT_MILLIS = 10_000
    private const val TARGET_PIXELS = 96

    private const val MEMORY_LIMIT = 48
    private const val RETRY_DELAY_MILLIS = 60_000L

    private val memory = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
                return size > MEMORY_LIMIT
            }
        },
    )

    private val broken = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val retryAfter = ConcurrentHashMap<String, Long>()

    private val localKeys = ConcurrentHashMap<String, String>()

    @Volatile
    private var agent: String? = null

    fun load(context: Context, url: String): ImageBitmap? {
        memory[url]?.let { return it }

        if (url in broken) return null

        val file = cacheFile(context, url)

        val bitmap = decodeScaled(file) ?: run {
            val now = System.currentTimeMillis()

            if ((retryAfter[url] ?: 0L) > now) return null

            if (!download(url, file, userAgent(context))) {
                retryAfter[url] = now + RETRY_DELAY_MILLIS

                return null
            }

            retryAfter.remove(url)

            decodeScaled(file).also { decoded ->
                if (decoded == null) {
                    broken.add(url)

                    file.delete()
                }
            }
        } ?: return null

        memory[url] = bitmap

        return bitmap
    }

    private fun cacheFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

        return File(File(context.cacheDir, DIRECTORY).apply { mkdirs() }, digest)
    }

    fun loadLocal(file: File): ImageBitmap? {
        val path = file.absolutePath
        val key = path + "|" + file.lastModified() + "|" + file.length()

        localKeys.put(path, key)?.takeIf { it != key }?.let { memory.remove(it) }

        memory[key]?.let { return it }

        return decodeScaled(file)?.also { memory[key] = it }
    }

    fun decodeScaled(file: File): ImageBitmap? {
        if (!file.exists() || file.length() == 0L) return null

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

            BitmapFactory.decodeFile(file.absolutePath, bounds)

            val larger = maxOf(bounds.outWidth, bounds.outHeight)

            var sample = 1
            while (larger / (sample * 2) >= TARGET_PIXELS) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply { inSampleSize = sample }

            BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
        }.getOrNull()
    }

    private fun userAgent(context: Context): String {
        agent?.let { return it }

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

        val value = "ClodClash/$version (Android)"

        if (version.isNotEmpty()) {
            agent = value
        }

        return value
    }

    private fun download(url: String, target: File, agent: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false

        if (!parsed.protocol.equals("https", ignoreCase = true)) return false

        val temporary = File(target.absolutePath + "." + UUID.randomUUID() + ".tmp")

        return try {
            val connection = parsed.openConnection() as HttpURLConnection

            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/*")
            connection.setRequestProperty("User-Agent", agent)

            connection.use { open ->
                if (open.responseCode !in 200..299) return false

                if (open.contentLength > MAX_BYTES) return false

                open.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        var total = 0
                        val buffer = ByteArray(16 * 1024)

                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break

                            total += read
                            if (total > MAX_BYTES) return false

                            output.write(buffer, 0, read)
                        }
                    }
                }
            }

            temporary.renameTo(target)
        } catch (e: Exception) {
            Log.w("Download group icon: $e", e)

            false
        } finally {
            temporary.delete()
        }
    }

    private inline fun <R> HttpURLConnection.use(block: (HttpURLConnection) -> R): R {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }
}
