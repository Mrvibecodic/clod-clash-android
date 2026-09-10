package com.github.kr328.clash.common.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Redirects {
    const val MAX_HOPS = 5

    sealed interface Step {
        object Done : Step

        data class Follow(val url: URL) : Step

        data class Refuse(val reason: String) : Step
    }

    fun step(from: URL, code: Int, location: String?, hop: Int): Step {
        if (code !in codes) return Step.Done

        if (hop >= MAX_HOPS) return Step.Refuse("refused redirect after $MAX_HOPS hops")

        if (location.isNullOrBlank()) return Step.Refuse("refused redirect without a location")

        val target = runCatching { URL(from, location) }.getOrNull()
            ?: return Step.Refuse("refused redirect to an unparsable location")

        if (from.protocol.equals("https", ignoreCase = true) &&
            !target.protocol.equals("https", ignoreCase = true)
        ) {
            return Step.Refuse("refused redirect from https to ${target.protocol}")
        }

        return Step.Follow(target)
    }

    fun open(
        url: String,
        openConnection: (URL) -> HttpURLConnection,
        configure: (HttpURLConnection) -> Unit,
    ): HttpURLConnection {
        var current = URL(url)
        var hop = 0

        while (true) {
            val connection = openConnection(current)

            connection.instanceFollowRedirects = false

            configure(connection)

            val step = try {
                step(current, connection.responseCode, connection.getHeaderField("Location"), hop)
            } catch (e: Throwable) {
                connection.disconnect()

                throw e
            }

            when (step) {
                is Step.Done -> return connection

                is Step.Refuse -> {
                    connection.disconnect()

                    throw IOException(step.reason)
                }

                is Step.Follow -> {
                    connection.disconnect()

                    current = step.url
                    hop += 1
                }
            }
        }
    }

    private val codes = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308,
    )
}
