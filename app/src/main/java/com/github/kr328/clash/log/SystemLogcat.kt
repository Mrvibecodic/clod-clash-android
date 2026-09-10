package com.github.kr328.clash.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object SystemLogcat {
    private val tags = arrayOf(
        "-s",
        "Go",
        "DEBUG",
        "AndroidRuntime",
        "ClodClash",
        "LwIP",
    )

    private const val MAX_LINES = 5000

    private const val MAX_CHARS = 512 * 1024

    private const val HEAD_LINES = MAX_LINES / 4

    private const val HEAD_CHARS = MAX_CHARS / 4

    private val WINDOW = TimeUnit.MINUTES.toMillis(10)

    private const val TRUNCATED = "--- %d lines in the middle dropped ---"

    fun dumpCrash(): String {
        val windowed = dump(arrayOf("logcat", "-t", since(WINDOW)) + tags)

        if (windowed.isNotEmpty()) return windowed

        return dump(arrayOf("logcat", "-d") + tags)
    }

    private fun since(window: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
            .format(Date(System.currentTimeMillis() - window))

    private fun dump(command: Array<String>): String {
        return try {
            val process = Runtime.getRuntime().exec(command)

            val result = process.inputStream.use { stream ->
                val clipped = stream.reader().useLines { lines ->
                    CrashLogClip.clip(
                        lines.filterNot { it.startsWith("------") },
                        HEAD_LINES,
                        HEAD_CHARS,
                        MAX_LINES - HEAD_LINES,
                        MAX_CHARS - HEAD_CHARS,
                    )
                }

                val text = ArrayList<String>(clipped.head.size + clipped.tail.size + 1)

                text.addAll(clipped.head)

                if (clipped.dropped > 0) {
                    text.add(TRUNCATED.format(clipped.dropped))
                }

                text.addAll(clipped.tail)

                text.joinToString("\n")
            }

            process.waitFor()

            result.trim()
        } catch (e: Exception) {
            ""
        }
    }
}
