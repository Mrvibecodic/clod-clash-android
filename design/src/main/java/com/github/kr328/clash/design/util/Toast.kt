package com.github.kr328.clash.design.util

import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.ui.ToastDuration

suspend fun Design<*>.showExceptionToast(message: CharSequence) {
    val safe = Redact.text(message.toString())

    showToast(safe, ToastDuration.Long, detail = safe)
}

suspend fun Design<*>.showExceptionToast(exception: Exception) {
    showExceptionToast(exception.message ?: "Unknown")
}
