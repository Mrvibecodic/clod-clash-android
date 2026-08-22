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
import java.util.concurrent.ConcurrentHashMap

object GroupIcons {
    private const val DIRECTORY = "group-icons"
    private const val MAX_BYTES = 512 * 1024
    private const val TIMEOUT_MILLIS = 10_000
    private const val TARGET_PIXELS = 96

    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun load(context: Context, url: String): ImageBitmap? {
        memory[url]?.let { return it }

        if (url in failed) return null

        val file = cacheFile(context, url)

        val bitmap = decode(file) ?: run {
            if (!download(url, file)) {
                failed.add(url)

                return null
            }

            decode(file)
        }

        if (bitmap == null) {
            failed.add(url)

            return null
        }

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

    private fun decode(file: File): ImageBitmap? {
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

    private fun download(url: String, target: File): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false

        if (!parsed.protocol.equals("https", ignoreCase = true)) return false

        val temporary = File(target.absolutePath + ".tmp")

        return try {
            val connection = parsed.openConnection() as HttpURLConnection

            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/*")

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
