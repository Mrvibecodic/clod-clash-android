package com.github.kr328.clash.design.compose.component

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

// До первого кадра узла ещё нет в дереве, и запрос фокуса падает
suspend fun FocusRequester.requestFocusAfterFrame(): Result<Unit> {
    withFrameNanos { }

    return runCatching<Unit> { requestFocus() }
}
