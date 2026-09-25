package com.github.kr328.clash.design.model

import java.security.MessageDigest

fun promoFingerprint(promo: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(promo.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
