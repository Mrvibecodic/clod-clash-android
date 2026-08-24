package com.github.kr328.clash.design.dialog

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.design.compose.component.ProgressContent
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.compose.theme.appDarkTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ModelProgressBarConfigure {
    var isIndeterminate: Boolean
    var text: String?
    var progress: Int
    var max: Int
}

interface ModelProgressBarScope {
    suspend fun configure(block: suspend ModelProgressBarConfigure.() -> Unit)
}

suspend fun Context.withModelProgressBar(block: suspend ModelProgressBarScope.() -> Unit) {
    var indeterminate by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var current by mutableIntStateOf(0)
    var maximum by mutableIntStateOf(0)

    val view = ComposeView(this).apply {
        setContent {
            ClodClashTheme(darkTheme = appDarkTheme()) {
                ProgressContent(
                    indeterminate = indeterminate,
                    progress = current,
                    max = maximum,
                    text = message,
                )
            }
        }
    }

    val dialog = MaterialAlertDialogBuilder(this)
        .setCancelable(false)
        .setView(view)
        .show()

    val configureImpl = object : ModelProgressBarConfigure {
        override var isIndeterminate: Boolean
            get() = indeterminate
            set(value) {
                indeterminate = value
            }
        override var text: String?
            get() = message
            set(value) {
                message = value
            }
        override var progress: Int
            get() = current
            set(value) {
                current = value
            }
        override var max: Int
            get() = maximum
            set(value) {
                maximum = value
            }
    }

    val scopeImpl = object : ModelProgressBarScope {
        override suspend fun configure(block: suspend ModelProgressBarConfigure.() -> Unit) {
            withContext(Dispatchers.Main) {
                configureImpl.block()
            }
        }
    }

    try {
        scopeImpl.block()
    } finally {
        dialog.dismiss()
    }
}
