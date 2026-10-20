package com.github.kr328.clash.service.util

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.UUID

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

    // Подписка (не uuid) с тем же адресом; у самой uuid этот адрес — null:
    // прежние дубли правке не мешают
    fun duplicateOf(uuid: UUID, source: String, subscriptions: List<Pair<UUID, String>>): UUID? {
        val key = sourceKey(source)

        if (subscriptions.any { (id, other) -> id == uuid && sourceKey(other) == key }) return null

        return subscriptions.firstOrNull { (id, other) -> id != uuid && sourceKey(other) == key }?.first
    }

    // Регистр пути не трогается: в нём токен подписки, и у панелей он регистрозависим.
    fun sourceKey(source: String): String {
        val text = source.trim()

        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return text
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return text
        val authority = uri.rawAuthority ?: return text
        val user = authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" }
        val hostPort = authority.substringAfterLast('@').lowercase(Locale.ROOT)
        val host = user + if (scheme == "https") hostPort.removeSuffix(":443") else hostPort
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()

        return "$scheme://$host${uri.rawPath.orEmpty().trimEnd('/')}$query"
    }
}
