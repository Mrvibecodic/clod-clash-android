package com.github.kr328.clash.common.util

import java.util.Locale

object AppLocale {
    @Volatile
    var current: Locale? = null

    fun formatting(): Locale = current ?: Locale.getDefault()
}
