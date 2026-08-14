package com.github.kr328.clash.design

import android.content.Context
import android.view.View
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
import com.github.kr328.clash.design.compose.screen.GeoFileState
import com.github.kr328.clash.design.compose.screen.MainAction
import com.github.kr328.clash.design.compose.screen.MainScreen
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.ProxyGroupState
import com.github.kr328.clash.design.compose.screen.SubScreen
import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.design.compose.screen.UpdateState
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        data object OpenProviders : Request
        data object OpenAccessControl : Request
        data object OpenLogs : Request

        /**
         * Экраны настроек. Раньше за ними стоял общий экран-список из четырёх
         * строк; теперь строки лежат прямо во вкладке «Ещё», и каждая ведёт
         * в свой экран напрямую.
         */
        data object OpenAppSettings : Request
        data object OpenNetworkSettings : Request
        data object OpenOverrideSettings : Request
        data object OpenMetaSettings : Request
        data object OpenHelp : Request

        /** Открыт экран «О приложении» — нужны версии и текущие настройки обновления. */
        data object LoadAbout : Request
        data class SetAutoCheckUpdate(val enabled: Boolean) : Request
        data class SetPrerelease(val enabled: Boolean) : Request

        /** Открыт экран «Данные маршрутизации» — нужен список файлов с диска. */
        data object LoadRoutingData : Request
        data object UpdateRoutingData : Request

        /** Перечитать имена групп: состав меняется при смене профиля. */
        data object ReloadProxies : Request
        data class ReloadGroup(val index: Int) : Request
        data class SelectProxy(val index: Int, val name: String) : Request
        data class ToggleFavorite(val name: String) : Request
        data class UrlTest(val index: Int) : Request
        data class PatchMode(val mode: TunnelState.Mode) : Request

        data class OpenUrl(val url: String) : Request
        data object CheckUpdate : Request
        data object UpdateNow : Request
        data object UpdateSkip : Request
        data object NewProfile : Request
        data object UpdateAllProfiles : Request
        data class ActivateProfile(val profile: Profile) : Request
        data class UpdateProfile(val profile: Profile) : Request
        data class EditProfile(val profile: Profile) : Request
        data class DeleteProfile(val profile: Profile) : Request
        data class SetSubscriptionGroup(val profile: Profile, val group: String?) : Request
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
            MainAction.OpenProviders -> request(Request.OpenProviders)
            MainAction.OpenAccessControl -> request(Request.OpenAccessControl)
            MainAction.OpenLogs -> request(Request.OpenLogs)
            MainAction.OpenAppSettings -> request(Request.OpenAppSettings)
            MainAction.OpenNetworkSettings -> request(Request.OpenNetworkSettings)
            MainAction.OpenOverrideSettings -> request(Request.OpenOverrideSettings)
            MainAction.OpenMetaSettings -> request(Request.OpenMetaSettings)
            MainAction.OpenHelp -> request(Request.OpenHelp)
            is MainAction.OpenSubScreen -> {
                state = state.copy(subScreen = action.screen)

                // Данные подтягиваются на открытии, а не заранее: держать в
                // памяти версию ядра и размеры файлов ради экрана, который
                // открывают раз в месяц, незачем.
                when (action.screen) {
                    SubScreen.About -> request(Request.LoadAbout)
                    SubScreen.RoutingData -> request(Request.LoadRoutingData)
                }
            }
            MainAction.CloseSubScreen -> state = state.copy(subScreen = null)
            is MainAction.SetAutoCheckUpdate -> {
                // Переключатель двигается сразу, не дожидаясь записи в хранилище:
                // иначе он подвисает под пальцем на время межпроцессного вызова.
                state = state.copy(about = state.about.copy(autoCheckUpdate = action.enabled))

                request(Request.SetAutoCheckUpdate(action.enabled))
            }
            is MainAction.SetPrerelease -> {
                state = state.copy(about = state.about.copy(prerelease = action.enabled))

                request(Request.SetPrerelease(action.enabled))
            }
            MainAction.UpdateRoutingData -> request(Request.UpdateRoutingData)
            MainAction.TestDelays -> request(Request.UrlTest(state.servers.selected))
            is MainAction.ToggleFavorite -> request(Request.ToggleFavorite(action.name))
            is MainAction.SetMode -> request(Request.PatchMode(action.mode))
            is MainAction.OpenUrl -> request(Request.OpenUrl(action.url))
            MainAction.CheckUpdate -> request(Request.CheckUpdate)
            MainAction.UpdateNow -> request(Request.UpdateNow)
            MainAction.UpdateSkip -> request(Request.UpdateSkip)
            // «Позже» — чисто экранное действие: окно закрывается, ничего
            // никуда не сообщается, при следующей проверке предложим снова.
            MainAction.UpdateLater -> state = state.copy(update = null)
            is MainAction.SelectSubscriptionGroup ->
                state = state.copy(
                    subscriptions = state.subscriptions.copy(selectedGroup = action.group),
                )

            is MainAction.SetSubscriptionGroup ->
                request(Request.SetSubscriptionGroup(action.profile, action.group))
            MainAction.NewProfile -> request(Request.NewProfile)
            MainAction.UpdateAllProfiles -> request(Request.UpdateAllProfiles)
            is MainAction.ActivateProfile -> request(Request.ActivateProfile(action.profile))
            is MainAction.UpdateProfile -> request(Request.UpdateProfile(action.profile))
            is MainAction.EditProfile -> request(Request.EditProfile(action.profile))
            is MainAction.DeleteProfile -> request(Request.DeleteProfile(action.profile))
            is MainAction.SelectGroup -> {
                state = state.copy(servers = state.servers.copy(selected = action.index))
                request(Request.ReloadGroup(action.index))
            }
            is MainAction.SelectProxy -> {
                val group = state.servers.groups.getOrNull(state.servers.selected)

                when {
                    state.servers.readOnly -> toast(R.string.clod_servers_direct)
                    group?.selectable != true -> toast(R.string.clod_select_not_selectable)
                    else -> {
                        request(Request.SelectProxy(state.servers.selected, action.name))

                        // До подключения выбор ничего видимого не меняет: узел
                        // просто ложится в базу. Без подсказки нажатие выглядит
                        // как несработавшее.
                        if (state.servers.offline) {
                            toast(R.string.clod_select_offline)
                        }
                    }
                }
            }

            // Переключение вкладки закрывает вложенный экран: он живёт внутри
            // вкладки, и оставлять его поверх соседней было бы враньём.
            is MainAction.SelectTab -> when (action.tab) {
                MainTab.Servers -> {
                    state = state.copy(selectedTab = MainTab.Servers, subScreen = null)
                    // Состав групп меняется при смене профиля, а список задержек
                    // протухает — перечитываем при каждом заходе на вкладку.
                    request(Request.ReloadProxies)
                }
                MainTab.Home, MainTab.More, MainTab.Subscriptions ->
                    state = state.copy(selectedTab = action.tab, subScreen = null)
            }
        }
    }

    /** Короткая подсказка вместо тишины, когда нажатие ничего не может сделать. */
    private fun toast(resId: Int) {
        launch { showToast(resId, ToastDuration.Long) }
    }

    suspend fun setActiveProfile(active: SubscriptionItem?) {
        withContext(Dispatchers.Main) {
            state = state.copy(active = active)
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

    suspend fun setUpdate(update: UpdateState?) {
        withContext(Dispatchers.Main) {
            state = state.copy(update = update)
        }
    }

    /** Прогресс загрузки обновления; отрицательный — размер неизвестен. */
    suspend fun setUpdateProgress(progress: Float) {
        withContext(Dispatchers.Main) {
            state = state.copy(
                update = state.update?.copy(downloading = true, progress = progress),
            )
        }
    }

    suspend fun setSessionSeconds(seconds: Long) {
        withContext(Dispatchers.Main) {
            state = state.copy(sessionSeconds = seconds)
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
    suspend fun setProxyGroupNames(
        names: List<String>,
        offline: Boolean = false,
        readOnly: Boolean = false,
    ) {
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
                    offline = offline,
                    readOnly = readOnly,
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

    suspend fun setFavorites(favorites: Set<String>) {
        withContext(Dispatchers.Main) {
            state = state.copy(servers = state.servers.copy(favorites = favorites))
        }
    }

    suspend fun setProxyTesting(testing: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(servers = state.servers.copy(testing = testing))
        }
    }

    /** Переключить вкладку снаружи: например, из подсказки «подписка не выбрана». */
    suspend fun selectTab(tab: MainTab) {
        withContext(Dispatchers.Main) {
            state = state.copy(selectedTab = tab)
        }
    }

    suspend fun setProfiles(profiles: List<SubscriptionItem>) {
        withContext(Dispatchers.Main) {
            state = state.copy(subscriptions = state.subscriptions.copy(profiles = profiles))
        }
    }

    /**
     * Есть ли хоть одна подписка.
     *
     * Спрашивается сразу после первой загрузки, чтобы не ходить в базу второй
     * раз: список уже прочитан и разложен по состоянию.
     */
    val hasProfiles: Boolean
        get() = state.subscriptions.profiles.isNotEmpty()

    /**
     * Какие подписки сейчас обновляются.
     *
     * Набор целиком, а не «добавь/убери»: он живёт в активити, где его сводят
     * запрос на обновление и широковещательное сообщение о завершении из
     * служебного процесса. Экрану остаётся показать то, что уже сведено.
     */
    suspend fun setUpdatingProfiles(uuids: Set<UUID>) {
        withContext(Dispatchers.Main) {
            state = state.copy(subscriptions = state.subscriptions.copy(updatingUuids = uuids))
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(hasProviders = has)
        }
    }

    /**
     * Только номер версии. Отдельно от [setAbout], потому что нужен раньше:
     * он стоит подписью к пункту «О приложении», а версию ядра до открытия
     * самого экрана спрашивать дорого.
     */
    suspend fun setAppVersion(versionName: String) {
        withContext(Dispatchers.Main) {
            state = state.copy(about = state.about.copy(versionName = versionName))
        }
    }

    suspend fun setAbout(
        versionName: String,
        coreVersion: String,
        autoCheckUpdate: Boolean,
        prerelease: Boolean,
    ) {
        withContext(Dispatchers.Main) {
            state = state.copy(
                about = state.about.copy(
                    versionName = versionName,
                    coreVersion = coreVersion,
                    autoCheckUpdate = autoCheckUpdate,
                    prerelease = prerelease,
                ),
            )
        }
    }

    /** Идёт проверка обновления: кнопка на экране «О приложении» занята. */
    suspend fun setUpdateChecking(checking: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(about = state.about.copy(checking = checking))
        }
    }

    suspend fun setRoutingData(files: List<GeoFileState>) {
        withContext(Dispatchers.Main) {
            state = state.copy(routingData = state.routingData.copy(files = files))
        }
    }

    suspend fun setRoutingDataUpdating(updating: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(routingData = state.routingData.copy(updating = updating))
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
