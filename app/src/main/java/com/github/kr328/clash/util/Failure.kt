package com.github.kr328.clash.util

import androidx.annotation.StringRes
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.showExceptionToast

internal fun failureText(e: Exception): String? =
    (e as? ServiceUnavailableException)?.message?.takeIf { it.isNotBlank() }

suspend fun Design<*>.showFailure(e: Exception, @StringRes headline: Int = R.string.clod_action_failed) {
    val text = failureText(e)

    if (text != null) {
        showExceptionToast(text)
    } else {
        showExceptionToast(e, headline)
    }
}
