package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.screen.MainAction
import com.github.kr328.clash.design.compose.screen.MainScreen
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.ProxyGroupState
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
    sealed interface Request {
        data object ToggleStatus : Request
        data object OpenProfiles : Request
        data object OpenProviders : Request
        data object OpenLogs : Request
        data object OpenSettings : Request
        data object OpenHelp : Request
        data object OpenAbout : Request

        /** Перечитать имена групп: состав меняется при смене профиля. */
        data object ReloadProxies : Request
        data class ReloadGroup(val index: Int) : Request
        data class SelectProxy(val index: Int, val name: String) : Request
        data class UrlTest(val index: Int) : Request
        data class PatchMode(val mode: TunnelState.Mode) : Request
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
            MainAction.OpenProfiles -> request(Request.OpenProfiles)
            MainAction.OpenProviders -> request(Request.OpenProviders)
            MainAction.OpenLogs -> request(Request.OpenLogs)
            MainAction.OpenSettings -> request(Request.OpenSettings)
            MainAction.OpenHelp -> request(Request.OpenHelp)
            MainAction.OpenAbout -> request(Request.OpenAbout)
            MainAction.TestDelays -> request(Request.UrlTest(state.servers.selected))
            is MainAction.SetMode -> request(Request.PatchMode(action.mode))
            is MainAction.SelectGroup -> {
                state = state.copy(servers = state.servers.copy(selected = action.index))
                request(Request.ReloadGroup(action.index))
            }
            is MainAction.SelectProxy ->
                request(Request.SelectProxy(state.servers.selected, action.name))

            is MainAction.SelectTab -> when (action.tab) {
                MainTab.Servers -> {
                    state = state.copy(selectedTab = MainTab.Servers)
                    // Состав групп меняется при смене профиля, а список задержек
                    // протухает — перечитываем при каждом заходе на вкладку.
                    request(Request.ReloadProxies)
                }
                // «Подписки» пока открывают старую Activity, поэтому вкладка не
                // переключается: иначе по «Назад» пользователь попадал бы на пустой экран.
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
            state = state.copy(mode = mode)
        }
    }

    /** Индекс группы, открытой на вкладке «Серверы». */
    val selectedGroup: Int
        get() = state.servers.selected

    /**
     * Имена групп. Уже загруженные узлы переносятся по совпадению имени: без
     * этого возврат на вкладку каждый раз мигал бы пустым списком, хотя данные
     * не изменились.
     */
    suspend fun setProxyGroupNames(names: List<String>) {
        withContext(Dispatchers.Main) {
            val previous = state.servers.groups.associateBy { it.name }
            val groups = names.map { name ->
                previous[name] ?: ProxyGroupState(
                    name = name,
                    now = "",
                    selectable = false,
                    proxies = emptyList(),
                )
            }
            state = state.copy(
                servers = state.servers.copy(
                    groups = groups,
                    selected = state.servers.selected.coerceIn(0, maxOf(groups.size - 1, 0)),
                ),
            )
        }
    }

    suspend fun setProxyGroup(index: Int, now: String, selectable: Boolean, proxies: List<Proxy>) {
        withContext(Dispatchers.Main) {
            val groups = state.servers.groups
            if (index !in groups.indices) return@withContext
            state = state.copy(
                servers = state.servers.copy(
                    groups = groups.toMutableList().also {
                        it[index] = it[index].copy(
                            now = now,
                            selectable = selectable,
                            proxies = proxies,
                        )
                    },
                ),
            )
        }
    }

    suspend fun setProxyTesting(testing: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(servers = state.servers.copy(testing = testing))
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
