package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.design.compose.screen.NewProfileAction
import com.github.kr328.clash.design.compose.screen.NewProfileScreen
import com.github.kr328.clash.design.compose.screen.NewProfileState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.model.ProfileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NewProfileDesign(context: Context) : Design<NewProfileDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request
        data class Create(val provider: ProfileProvider) : Request
        data class OpenDetail(val provider: ProfileProvider.External) : Request
        data class LaunchScanner(val provider: ProfileProvider.QR) : Request
    }

    private var state by mutableStateOf(NewProfileState())

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                NewProfileScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: NewProfileAction) {
        when (action) {
            NewProfileAction.Back -> requests.trySend(Request.Back)
            is NewProfileAction.Select -> {
                val provider = action.provider

                if (provider is ProfileProvider.QR) {
                    requests.trySend(Request.LaunchScanner(provider))
                } else {
                    requests.trySend(Request.Create(provider))
                }
            }
            is NewProfileAction.Detail -> {
                // Сведения о приложении есть только у внешних поставщиков:
                // у файла, ссылки и QR открывать нечего.
                val provider = action.provider as? ProfileProvider.External ?: return

                requests.trySend(Request.OpenDetail(provider))
            }
        }
    }

    suspend fun patchProviders(providers: List<ProfileProvider>) {
        withContext(Dispatchers.Main) {
            state = state.copy(providers = providers)
        }
    }
}
