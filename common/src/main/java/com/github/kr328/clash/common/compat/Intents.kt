package com.github.kr328.clash.common.compat

import android.app.PendingIntent

fun pendingIntentFlags(flags: Int): Int = flags or PendingIntent.FLAG_IMMUTABLE
