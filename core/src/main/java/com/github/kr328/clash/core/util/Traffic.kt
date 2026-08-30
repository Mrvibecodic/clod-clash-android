package com.github.kr328.clash.core.util

import com.github.kr328.clash.core.model.Traffic
import java.util.Locale

fun Traffic.bytesUpload(): Long {
    return bytesTraffic(this ushr 32)
}

fun Traffic.bytesDownload(): Long {
    return bytesTraffic(this and 0xFFFFFFFF)
}

fun Traffic.trafficUpload(): String {
    return trafficString(scaleTraffic(this ushr 32))
}

fun Traffic.trafficDownload(): String {
    return trafficString(scaleTraffic(this and 0xFFFFFFFF))
}

fun Traffic.trafficTotal(): String {
    val upload = scaleTraffic(this ushr 32)
    val download = scaleTraffic(this and 0xFFFFFFFF)

    return trafficString(upload + download)
}

private fun trafficString(scaled: Long): String {
    val locale = Locale.getDefault()

    return when {
        scaled > 1024 * 1024 * 1024 * 100L -> {
            val data = scaled / 1024 / 1024 / 1024

            String.format(locale, "%.2f GiB", data.toFloat() / 100)
        }
        scaled > 1024 * 1024 * 100L -> {
            val data = scaled / 1024 / 1024

            String.format(locale, "%.2f MiB", data.toFloat() / 100)
        }
        scaled > 1024 * 100L -> {
            val data = scaled / 1024

            String.format(locale, "%.2f KiB", data.toFloat() / 100)
        }
        else -> {
            "$scaled Bytes"
        }
    }
}

private fun bytesTraffic(value: Long): Long {
    val scaled = scaleTraffic(value)

    return if (((value ushr 30) and 0x3) == 0L) scaled else scaled / 100
}

private fun scaleTraffic(value: Long): Long {
    val type = (value ushr 30) and 0x3
    val data = value and 0x3FFFFFFF

    return when (type) {
        0L -> data
        1L -> data * 1024
        2L -> data * 1024 * 1024
        3L -> data * 1024 * 1024 * 1024
        else -> throw IllegalArgumentException("invalid value type")
    }
}
