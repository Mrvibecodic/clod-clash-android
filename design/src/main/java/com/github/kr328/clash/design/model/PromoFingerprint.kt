package com.github.kr328.clash.design.model

import com.github.kr328.clash.common.util.sha256Hex

fun promoFingerprint(promo: String): String = sha256Hex(promo)
