package com.github.kr328.clash.common.util

object Redact {
    private const val TRAILING = ".,;:!?)]}\"'"

    private const val MASK = "/***"

    fun text(value: String?): String {
        val input = value ?: return ""

        if (input.isEmpty() || !input.contains("://")) {
            return input
        }

        val builder = StringBuilder(input.length)

        var index = 0

        while (index < input.length) {
            val separator = input.indexOf("://", index)

            if (separator < 0) {
                builder.append(input, index, input.length)

                return builder.toString()
            }

            var start = separator
            while (start > index && isSchemeChar(input[start - 1])) {
                start--
            }

            val scheme = input.substring(start, separator).lowercase()

            if (scheme != "http" && scheme != "https") {
                builder.append(input, index, separator + 3)

                index = separator + 3

                continue
            }

            var end = separator + 3
            while (end < input.length && !isDelimiter(input[end])) {
                end++
            }

            var stop = end
            while (stop > separator + 3 && input[stop - 1] in TRAILING) {
                stop--
            }

            builder.append(input, index, separator + 3)
            builder.append(maskBody(input.substring(separator + 3, stop)))
            builder.append(input, stop, end)

            index = end
        }

        return builder.toString()
    }

    private fun maskBody(body: String): String {
        val cut = body.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut >= 0) body.substring(0, cut) else body
        val host = authority.substringAfterLast('@')
        val remainder = body.substring(authority.length)
        val hasPath = remainder.startsWith("/") && remainder.trim('/').isNotEmpty()
        val hidden = host != authority ||
            hasPath ||
            remainder.contains('?') ||
            remainder.contains('#')

        if (!hidden) {
            return body
        }

        return if (hasPath) host + MASK else host
    }

    private fun isSchemeChar(value: Char): Boolean {
        return value in 'a'..'z' ||
            value in 'A'..'Z' ||
            value in '0'..'9' ||
            value == '+' ||
            value == '-' ||
            value == '.'
    }

    private fun isDelimiter(value: Char): Boolean {
        return value.isWhitespace() ||
            value == '"' ||
            value == '\'' ||
            value == '<' ||
            value == '>' ||
            value == '\\' ||
            value == '`'
    }
}
