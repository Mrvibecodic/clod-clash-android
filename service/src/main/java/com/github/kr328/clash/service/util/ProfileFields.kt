package com.github.kr328.clash.service.util

object ProfileFields {
    const val NAME_MAX = 1024

    const val SOURCE_MAX = 2048

    enum class Violation {
        EmptyName,
        NameTooLong,
        EmptySource,
        SourceTooLong,
        UnsupportedScheme,
        ShortInterval,
    }

    fun violation(
        name: String,
        source: String,
        scheme: String?,
        interval: Long,
        requiresSource: Boolean,
    ): Violation? = when {
        name.isBlank() -> Violation.EmptyName

        name.length > NAME_MAX -> Violation.NameTooLong

        source.isEmpty() && requiresSource -> Violation.EmptySource

        source.length > SOURCE_MAX -> Violation.SourceTooLong

        source.isNotEmpty() && scheme != "https" && scheme != "content" -> Violation.UnsupportedScheme

        interval != 0L && interval < UpdateSchedule.MIN_INTERVAL -> Violation.ShortInterval

        else -> null
    }
}
