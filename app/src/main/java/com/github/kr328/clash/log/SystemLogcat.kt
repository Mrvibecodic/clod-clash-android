package com.github.kr328.clash.log

object SystemLogcat {
    private val command = arrayOf(
        "logcat",
        "-d",
        "-s",
        "Go",
        "DEBUG",
        "AndroidRuntime",
        "ClodClash",
        "LwIP",
    )

    private const val MAX_LINES = 5000

    private const val MAX_CHARS = 512 * 1024

    private const val TRUNCATED = "--- head of the log dropped, tail only ---"

    fun dumpCrash(): String {
        return try {
            val process = Runtime.getRuntime().exec(command)

            val result = process.inputStream.use { stream ->
                val tail = ArrayDeque<String>()

                var chars = 0

                var dropped = false

                stream.reader().forEachLine { line ->
                    if (line.startsWith("------")) return@forEachLine

                    tail.addLast(line)

                    chars += line.length + 1

                    while (tail.size > MAX_LINES || chars > MAX_CHARS) {
                        chars -= tail.removeFirst().length + 1

                        dropped = true
                    }
                }

                if (dropped) tail.addFirst(TRUNCATED)

                tail.joinToString("\n")
            }

            process.waitFor()

            result.trim()
        } catch (e: Exception) {
            ""
        }
    }
}
