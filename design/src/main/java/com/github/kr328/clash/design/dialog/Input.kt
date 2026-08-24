package com.github.kr328.clash.design.dialog

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.TextInputContent
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.compose.theme.appDarkTheme
import com.github.kr328.clash.design.util.Validator
import com.github.kr328.clash.design.util.ValidatorAcceptAll
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun Context.requestModelTextInput(
    initial: String,
    title: CharSequence,
    hint: CharSequence? = null,
    error: CharSequence? = null,
    validator: Validator = ValidatorAcceptAll,
): String {
    return this.requestModelTextInput(initial, title, null, hint, error, validator)!!
}

suspend fun Context.requestModelTextInput(
    initial: String?,
    title: CharSequence,
    reset: CharSequence?,
    hint: CharSequence? = null,
    error: CharSequence? = null,
    validator: Validator = ValidatorAcceptAll,
): String? {
    return suspendCancellableCoroutine { continuation ->
        var current = initial ?: ""
        var dialog: AlertDialog? = null

        val view = ComposeView(this).apply {
            setContent {
                ClodClashTheme(darkTheme = appDarkTheme()) {
                    TextInputContent(
                        initial = initial ?: "",
                        isValid = validator,
                        onChanged = { text, valid ->
                            current = text

                            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = valid
                        },
                        hint = hint?.toString(),
                        error = error?.toString(),
                    )
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setCancelable(true)
            .setPositiveButton(R.string.ok) { _, _ ->
                continuation.resume(if (validator(current)) current else initial)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .setOnDismissListener {
                if (!continuation.isCompleted) {
                    continuation.resume(initial)
                }
            }

        if (reset != null) {
            builder.setNeutralButton(reset) { _, _ ->
                continuation.resume(null)
            }
        }

        val created = builder.create()

        dialog = created

        continuation.invokeOnCancellation {
            created.dismiss()
        }

        created.show()

        created.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = validator(current)
    }
}
