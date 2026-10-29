package com.github.kr328.clash.common.util

import java.security.MessageDigest

fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray())
