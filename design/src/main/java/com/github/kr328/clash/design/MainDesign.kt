package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.screen.MainAction
import com.github.kr328.clash.design.compose.screen.MainScreen
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.util.layoutInflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Главный экран. Первый экран, переехавший с XML+DataBinding на Compose.
 *
 * Публичный контракт намеренно оставлен прежним — набор suspend-сеттеров плюс
 * канал [requests]: `MainActivity` не должна знать, чем нарисован экран, и при
 * переезде остальных экранов её цикл событий не переписывается каждый раз.
 */
class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
    }

    /**
     * Состояние экрана одним снимком, а не отдельным состоянием на каждое поле:
     * Compose получает ровно одно изменение на обновление, и рекомпозиция не идёт
     * по нескольку раз на один тик трафика.
     */
    private var state by mutableStateOf(MainScreenState())

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                MainScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: MainAction) {
        when (action) {
            MainAction.ToggleStatus -> request(Request.ToggleStatus)
            MainAction.OpenProxy -> request(Request.OpenProxy)
            MainAction.OpenProfiles -> request(Request.OpenProfiles)
            MainAction.OpenProviders -> request(Request.OpenProviders)
            MainAction.OpenLogs -> request(Request.OpenLogs)
            MainAction.OpenSettings -> request(Request.OpenSettings)
            MainAction.OpenHelp -> request(Request.OpenHelp)
            MainAction.OpenAbout -> request(Request.OpenAbout)
            is MainAction.SelectTab -> when (action.tab) {
                // «Серверы» и «Подписки» пока открывают старые Activity, поэтому
                // вкладка не переключается: иначе, вернувшись назад, пользователь
                // увидел бы пустой экран выбранной вкладки.
                MainTab.Servers -> request(Request.OpenProxy)
                MainTab.Subscriptions -> request(Request.OpenProfiles)
                MainTab.Home, MainTab.More -> state = state.copy(selectedTab = action.tab)
            }
        }
    }

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            state = state.copy(profileName = name)
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(
                status = if (running) ConnectionStatus.Connected else ConnectionStatus.Disconnected,
            )
        }
    }

    /**
     * Промежуточное состояние: команда на запуск отдана, туннель ещё не поднялся.
     * Сбрасывается следующим [setClashRunning] — его присылает событие службы.
     */
    suspend fun setConnecting() {
        withContext(Dispatchers.Main) {
            if (state.status == ConnectionStatus.Disconnected) {
                state = state.copy(status = ConnectionStatus.Connecting)
            }
        }
    }

    suspend fun setTraffic(value: Traffic) {
        withContext(Dispatchers.Main) {
            state = state.copy(
                downloaded = value.trafficDownload(),
                uploaded = value.trafficUpload(),
            )
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            state = state.copy(
                mode = when (mode) {
                    TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                    TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                    TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                    else -> context.getString(R.string.rule_mode)
                },
            )
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(hasProviders = has)
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
