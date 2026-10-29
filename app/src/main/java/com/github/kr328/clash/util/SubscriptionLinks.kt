package com.github.kr328.clash.util

import android.net.Uri

// Адрес подписки из набранного текста или ссылки: https как есть; у ссылок
// clash://, clashmeta://, clodclash:// (install-config) — параметр url, тоже
// только https. Иначе null — длину проверяет вызывающий
fun subscriptionSourceOf(text: String, unwrap: Boolean = true): String? {
    val trimmed = text.trim()

    if (trimmed.isEmpty()) return null

    val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null

    return when (uri.scheme?.lowercase()) {
        "https" -> trimmed
        "clash", "clashmeta", "clodclash" -> if (unwrap) {
            // Ссылка без «//» (clash:install-config?url=…) — не иерархическая, параметров у неё нет
            runCatching { uri.getQueryParameter("url") }.getOrNull()?.let { subscriptionSourceOf(it, unwrap = false) }
        } else {
            null
        }

        else -> null
    }
}
