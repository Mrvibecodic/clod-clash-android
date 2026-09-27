package com.github.kr328.clash.util

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

fun Context.applyHideFromRecents(hide: Boolean) {
    checkNotNull(getSystemService<ActivityManager>()).appTasks.forEach { task ->
        task.setExcludeFromRecents(hide)
    }
}
