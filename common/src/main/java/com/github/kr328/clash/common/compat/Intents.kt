package com.github.kr328.clash.common.compat

import android.app.PendingIntent
import android.os.Build

fun pendingIntentFlags(flags: Int): Int {
    return if (Build.VERSION.SDK_INT >= 24) {
        flags or PendingIntent.FLAG_IMMUTABLE
    } else {
        flags
    }
}
