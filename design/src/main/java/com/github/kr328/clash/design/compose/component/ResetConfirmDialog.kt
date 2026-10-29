package com.github.kr328.clash.design.compose.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.kr328.clash.design.R

@Composable
fun ResetConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.reset_override_settings),
        text = stringResource(R.string.reset_override_settings_message),
        confirm = stringResource(R.string.clod_reset_action),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
