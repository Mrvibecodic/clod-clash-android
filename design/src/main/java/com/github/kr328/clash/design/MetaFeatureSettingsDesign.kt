package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.getSystemService
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsAction
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsScreen
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.databinding.DialogAgeKeyHelperBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MetaFeatureSettingsDesign(
    context: Context,
    private val configuration: ConfigurationOverride,
) : Design<MetaFeatureSettingsDesign.Request>(context) {
    enum class Request {
        ResetOverride, ImportGeoIp, ImportGeoSite, ImportCountry, ImportASN, Back
    }

    private var state by mutableStateOf(MetaFeatureSettingsState(configuration))

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                MetaFeatureSettingsScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: MetaFeatureSettingsAction) {
        when (action) {
            MetaFeatureSettingsAction.Back -> requests.trySend(Request.Back)
            MetaFeatureSettingsAction.Reset -> requests.trySend(Request.ResetOverride)
            MetaFeatureSettingsAction.Changed -> state = state.copy(revision = state.revision + 1)
            is MetaFeatureSettingsAction.OpenAgeKeys -> requestAgeKeyHelper(action.hybrid)
            MetaFeatureSettingsAction.ImportGeoIp -> requests.trySend(Request.ImportGeoIp)
            MetaFeatureSettingsAction.ImportGeoSite -> requests.trySend(Request.ImportGeoSite)
            MetaFeatureSettingsAction.ImportCountry -> requests.trySend(Request.ImportCountry)
            MetaFeatureSettingsAction.ImportAsn -> requests.trySend(Request.ImportASN)
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

    private fun requestAgeKeyHelper(hybrid: Boolean) {
        launch(Dispatchers.Main) {
            val binding = DialogAgeKeyHelperBinding
                .inflate(context.layoutInflater, context.root, false)
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(if (hybrid) R.string.age_key_type_hybrid else R.string.age_key_type_x25519)
                .setView(binding.root)
                .create()

            fun copy(label: String, value: String) {
                if (value.isBlank())
                    return

                val data = ClipData.newPlainText(label, value)
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(data)

                launch { showToast(R.string.copied, ToastDuration.Short) }
            }

            fun patchSecretKeyState() {
                val secretKey = binding.secretKeyView.text?.toString() ?: ""
                val valid = secretKey.isBlank() || Clash.veritySecretKeys(secretKey)

                binding.secretKeyLayout.error = if (valid) null else context.getText(R.string.age_secret_key_error)
            }

            fun patchPublicKeyState() {
                val publicKey = binding.publicKeyView.text?.toString() ?: ""
                val valid = publicKey.isBlank() || Clash.verityPublicKeys(publicKey)

                binding.publicKeyLayout.error = if (valid) null else context.getText(R.string.age_public_key_error)
            }

            dialog.setOnShowListener {
                binding.secretKeyView.doOnTextChanged { _, _, _, _ -> patchSecretKeyState() }
                binding.publicKeyView.doOnTextChanged { _, _, _, _ -> patchPublicKeyState() }

                binding.generateView.setOnClickListener {
                    val keyPair = if (hybrid) {
                        Clash.genHybridKeyPair()
                    } else {
                        Clash.genX25519KeyPair()
                    }

                    binding.secretKeyView.setText(keyPair.secretKey)
                    binding.publicKeyView.setText(keyPair.publicKey)
                }

                binding.toPublicKeyView.setOnClickListener {
                    val publicKey = Clash.toPublicKeys(binding.secretKeyView.text?.toString() ?: "")
                        .firstOrNull()
                        ?: ""

                    binding.publicKeyView.setText(publicKey)
                }

                binding.copySecretKeyView.setOnClickListener {
                    copy("age_secret_key", binding.secretKeyView.text?.toString() ?: "")
                }

                binding.copyPublicKeyView.setOnClickListener {
                    copy("age_public_key", binding.publicKeyView.text?.toString() ?: "")
                }
            }

            dialog.show()
        }
    }
}
