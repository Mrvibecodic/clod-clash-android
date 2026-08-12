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
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.compose.component.AgeKeyContent
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsAction
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsScreen
import com.github.kr328.clash.design.compose.screen.MetaFeatureSettingsState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.ui.ToastDuration
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

    /**
     * Помощник по ключам age: сгенерировать пару, вывести открытый ключ
     * из секретного, скопировать любой из них.
     *
     * Кнопок подтверждения у диалога нет и не было: значения отсюда уносят
     * буфером обмена, а не «сохранением», — поля в настройках человек
     * заполняет сам.
     */
    private fun requestAgeKeyHelper(hybrid: Boolean) {
        launch(Dispatchers.Main) {
            var secretKey by mutableStateOf("")
            var publicKey by mutableStateOf("")

            fun copy(label: String, value: String) {
                if (value.isBlank())
                    return

                val data = ClipData.newPlainText(label, value)
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(data)

                launch { showToast(R.string.copied, ToastDuration.Short) }
            }

            val view = ComposeView(context).apply {
                setContent {
                    ClodClashTheme {
                        AgeKeyContent(
                            secretKey = secretKey,
                            publicKey = publicKey,
                            onSecretKeyChange = { secretKey = it },
                            onPublicKeyChange = { publicKey = it },
                            // Не ссылками на методы: у них vararg,
                            // и к `(String) -> Boolean` он не приводится.
                            isSecretValid = { Clash.veritySecretKeys(it) },
                            isPublicValid = { Clash.verityPublicKeys(it) },
                            onGenerate = {
                                val keyPair = if (hybrid) {
                                    Clash.genHybridKeyPair()
                                } else {
                                    Clash.genX25519KeyPair()
                                }

                                secretKey = keyPair.secretKey
                                publicKey = keyPair.publicKey
                            },
                            onDerivePublic = {
                                publicKey = Clash.toPublicKeys(secretKey).firstOrNull() ?: ""
                            },
                            onCopySecret = { copy("age_secret_key", secretKey) },
                            onCopyPublic = { copy("age_public_key", publicKey) },
                            secretLabel = context.getString(R.string.age_secret_key),
                            publicLabel = context.getString(R.string.age_public_key),
                            secretError = context.getString(R.string.age_secret_key_error),
                            publicError = context.getString(R.string.age_public_key_error),
                            generateLabel = context.getString(R.string.age_key_generate),
                            derivePublicLabel = context.getString(R.string.age_key_to_public),
                            copyLabel = context.getString(R.string.age_key_copy),
                        )
                    }
                }
            }

            MaterialAlertDialogBuilder(context)
                .setTitle(if (hybrid) R.string.age_key_type_hybrid else R.string.age_key_type_x25519)
                .setView(view)
                .show()
        }
    }
}
