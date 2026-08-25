package com.github.kr328.clash.design.util

import com.github.kr328.clash.common.util.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    private val cases = mapOf(
        "" to "",
        "без ссылок" to "без ссылок",
        "https://panel.example.com" to "https://panel.example.com",
        "https://panel.example.com/" to "https://panel.example.com/",
        "https://panel.example.com/?a" to "https://panel.example.com/***",
        "Get \"https://panel.example.com/abcdef?token=1\": dial tcp" to
            "Get \"https://panel.example.com/***\": dial tcp",
        "https://user:pass@panel.example.com/sub" to "https://panel.example.com/***",
        "https://user:pass@panel.example.com" to "https://panel.example.com",
        "смотри https://host.example/a/b/c, потом ещё раз" to
            "смотри https://host.example/***, потом ещё раз",
        "https://dns.example.com/dns-query?token=abc" to "https://dns.example.com/***",
        "tls://dot.example.com:853" to "tls://dot.example.com:853",
        "tg://resolve?domain=provider_bot" to "tg://resolve?domain=provider_bot",
        "content://com.android.providers/document/1" to "content://com.android.providers/document/1",
        "mailto:support@example.com" to "mailto:support@example.com",
        "http://127.0.0.1:7890" to "http://127.0.0.1:7890",
        "два https://a.b/c и https://d.e/f?g" to "два https://a.b/*** и https://d.e/***",
        "https://h/p%?token=SECRET" to "https://h/***",
        "https://[fe80::1]/p?token=SECRET" to "https://[fe80::1]/***",
        "HTTPS://Panel.Example.COM/sub?token=1" to "HTTPS://Panel.Example.COM/***",
        "https://пример.рф/sub?token=1" to "https://пример.рф/***",
        "строка https://a.b/c?d\nвторая https://e.f/g?h" to
            "строка https://a.b/***\nвторая https://e.f/***",
    )

    @Test
    fun `адрес подписки прячется, остальное остаётся`() {
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, Redact.text(input))
        }
    }

    @Test
    fun `повторная вычистка ничего не меняет`() {
        cases.keys.forEach { input ->
            val once = Redact.text(input)

            assertEquals(once, once, Redact.text(once))
        }
    }

    @Test
    fun `длинный адрес не оставляет хвоста`() {
        val long = "Get \"https://panel.example.com/" + "a".repeat(200_000) + "?token=SECRET\""

        val masked = Redact.text(long)

        assertFalse(masked.contains("SECRET"))
        assertFalse(masked.contains("aaaa"))
    }
}
