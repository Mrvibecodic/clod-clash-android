package com.github.kr328.clash.design.util

import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.compose.component.NoticeKind
import com.github.kr328.clash.design.ui.ToastDuration

suspend fun Design<*>.showExceptionToast(message: CharSequence) {
    val safe = Redact.text(message.toString())

    showToast(safe, ToastDuration.Long, detail = safe, kind = NoticeKind.Error)
}

suspend fun Design<*>.showExceptionToast(exception: Exception) {
    showExceptionToast(exception.message ?: "Unknown")
}
