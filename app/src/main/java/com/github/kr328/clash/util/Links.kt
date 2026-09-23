package com.github.kr328.clash.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.NoticeKind
import com.github.kr328.clash.design.ui.ToastDuration

fun Context.startExternal(intent: Intent): Boolean = try {
    startActivity(intent)

    true
} catch (e: ActivityNotFoundException) {
    false
}

suspend fun Design<*>.showNoAppForLink(url: String) {
    showToast(R.string.clod_link_no_app, ToastDuration.Long, detail = url, kind = NoticeKind.Error)
}
