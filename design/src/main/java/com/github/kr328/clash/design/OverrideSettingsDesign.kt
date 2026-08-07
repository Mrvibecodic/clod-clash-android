package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.compose.screen.OverrideSettingsAction
import com.github.kr328.clash.design.compose.screen.OverrideSettingsScreen
import com.github.kr328.clash.design.compose.screen.OverrideSettingsState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class OverrideSettingsDesign(
    context: Context,
    private val configuration: ConfigurationOverride,
) : Design<OverrideSettingsDesign.Request>(context) {
    sealed interface Request {
        data object ResetOverride : Request
        data object Back : Request
    }

    private var state by mutableStateOf(OverrideSettingsState(configuration))

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                OverrideSettingsScreen(state = state, onAction = ::onAction)
            }
        }
    }

    suspend fun requestResetConfirm(): Boolean {
        return suspendCancellableCoroutine { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.reset_override_settings)
                .setMessage(R.string.reset_override_settings_message)
                .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()

            dialog.setOnDismissListener {
                if (!ctx.isCompleted) {
                    ctx.resume(false)
                }
            }

            ctx.invokeOnCancellation {
                dialog.dismiss()
            }
        }
    }

    private fun onAction(action: OverrideSettingsAction) {
        when (action) {
            OverrideSettingsAction.Back -> requests.trySend(Request.Back)
            OverrideSettingsAction.Reset -> requests.trySend(Request.ResetOverride)
            // Объект настроек правится на месте — экрану остаётся сказать,
            // что пора перерисоваться. Сохраняет его активити целиком,
            // когда экран закрывают.
            OverrideSettingsAction.Changed -> state = state.copy(revision = state.revision + 1)
        }
    }
}
