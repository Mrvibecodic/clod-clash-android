package com.github.kr328.clash.core.util

import java.util.Locale

private val UNITS = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

fun Long.toBytesString(): String {
    if (this < 1024) return "$this Bytes"

    var value = this.toDouble() / 1024
    var unit = 0

    while (unit < UNITS.lastIndex && value >= 1023.5) {
        value /= 1024
        unit++
    }

    val digits = when {
        value >= 100 -> 0
        value >= 10 -> 1
        else -> 2
    }

    return String.format(Locale.ROOT, "%.${digits}f %s", value, UNITS[unit])
}
