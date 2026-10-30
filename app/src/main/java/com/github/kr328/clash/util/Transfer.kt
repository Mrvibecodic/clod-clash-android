package com.github.kr328.clash.util

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.net.Redirects
import com.github.kr328.clash.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy

object Transfer {
    class TooLarge(message: String) : IOException(message)

    fun localProxy(port: Int): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))

    // Маршрут null — системный выбор прокси. Маршруты перебираются, пока read не
    // вернёт значение; TooLarge и ошибка, на которой stop вернул true, перебор обрывают
    suspend fun <T : Any> firstRoute(
        tag: String,
        url: String,
        routes: List<Proxy?>,
        connectTimeout: Int,
        readTimeout: Int,
        stop: (Throwable) -> Boolean = { false },
        read: suspend (HttpURLConnection) -> T?,
    ): T? {
        for (route in routes) {
            currentCoroutineContext().ensureActive()

            val result = runCatching {
                val connection = Redirects.open(
                    url,
                    { address -> (if (route != null) address.openConnection(route) else address.openConnection()) as HttpURLConnection },
                    { open ->
                        open.connectTimeout = connectTimeout
                        open.readTimeout = readTimeout
                        open.setRequestProperty("User-Agent", Updater.USER_AGENT)
                    },
                )

                try {
                    if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")

                    read(connection)
                } finally {
                    connection.disconnect()
                }
            }.onFailure {
                if (it is CancellationException || stop(it)) throw it

                Log.w("$tag: $url via ${route ?: "system route"}: $it", it)
            }

            if (result.exceptionOrNull() is TooLarge) return null

            result.getOrNull()?.let { return it }
        }

        return null
    }

    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        onChunk: (buffer: ByteArray, read: Int, copied: Long) -> Unit = { _, _, _ -> },
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L

        while (true) {
            currentCoroutineContext().ensureActive()

            val read = input.read(buffer)
            if (read < 0) return copied

            copied += read
            if (copied > limit) throw TooLarge("more than $limit bytes")

            output.write(buffer, 0, read)
            onChunk(buffer, read, copied)
        }
    }
}
