package com.github.kr328.clash.core.util

import com.github.kr328.clash.core.model.Traffic

fun Traffic.bytesUpload(): Long {
    return bytesTraffic(this ushr 32)
}

fun Traffic.bytesDownload(): Long {
    return bytesTraffic(this and 0xFFFFFFFF)
}

fun Traffic.trafficUpload(): String {
    return bytesUpload().toBytesString()
}

fun Traffic.trafficDownload(): String {
    return bytesDownload().toBytesString()
}

fun Traffic.trafficTotal(): String {
    return (bytesUpload() + bytesDownload()).toBytesString()
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
