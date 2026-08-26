package com.github.kr328.clash.design

import android.content.Context
import android.text.format.Formatter
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.bytesDownload
import com.github.kr328.clash.core.util.bytesUpload
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.screen.GeoFileState
import com.github.kr328.clash.design.compose.screen.ProviderFileState
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.compose.screen.MainAction
import com.github.kr328.clash.design.compose.screen.MainScreen
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.ProxyGroupState
import com.github.kr328.clash.design.compose.screen.SubScreen
import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.design.compose.screen.UpdateState
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainDesign(
    context: Context,
    initial: MainScreenState = MainScreenState(),
) : Design<MainDesign.Request>(context) {
    sealed interface Request {
        data object ToggleStatus : Request
        data object OpenAccessControl : Request
        data object OpenLogs : Request

        data object OpenAppSettings : Request
        data object OpenNetworkSettings : Request
        data object OpenMetaSettings : Request
        data object OpenHelp : Request

        data object LoadAbout : Request
        data class SetAutoCheckUpdate(val enabled: Boolean) : Request
        data class SetPrerelease(val enabled: Boolean) : Request

        data object LoadRoutingData : Request
        data object UpdateRoutingData : Request

        data object ReloadProxies : Request
        data class ReloadGroup(val index: Int) : Request
        data class SelectProxy(val index: Int, val name: String) : Request
        data class ToggleFavorite(val name: String) : Request
        data object UrlTest : Request
        data class PatchMode(val mode: TunnelState.Mode) : Request

        data class OpenUrl(val url: String) : Request
        data object CheckUpdate : Request
        data object UpdateNow : Request
        data object UpdateSkip : Request
        data object UpdateLater : Request
        data object NewProfile : Request
        data object UpdateAllProfiles : Request
        data class ActivateProfile(val profile: Profile) : Request
        data class UpdateProfile(val profile: Profile) : Request
        data class EditProfile(val profile: Profile) : Request
        data class DeleteProfile(val profile: Profile) : Request
        data class SetSubscriptionGroup(val profile: Profile, val group: String?) : Request
        data object AllowNotifications : Request
        data object SkipNotifications : Request
        data object DismissNotifications : Request
        data object ReliabilityAllowBattery : Request
        data object ReliabilityOpenVpnSettings : Request
        data object ReliabilityDismiss : Request
    }

    private var state by mutableStateOf(initial)

    override val root: View = composeRoot(noticeInset = 80.dp) {
        MainScreen(state = state, onAction = ::onAction)
    }

    private fun onAction(action: MainAction) {
        when (action) {
            MainAction.ToggleStatus -> request(Request.ToggleStatus)
            MainAction.AllowNotifications -> request(Request.AllowNotifications)
            MainAction.SkipNotifications -> request(Request.SkipNotifications)
            MainAction.DismissNotifications -> request(Request.DismissNotifications)
            MainAction.ReliabilityAllowBattery -> request(Request.ReliabilityAllowBattery)
            MainAction.ReliabilityOpenVpnSettings -> request(Request.ReliabilityOpenVpnSettings)
            MainAction.ReliabilityDismiss -> request(Request.ReliabilityDismiss)
            MainAction.OpenAccessControl -> request(Request.OpenAccessControl)
            MainAction.OpenLogs -> request(Request.OpenLogs)
            MainAction.OpenAppSettings -> request(Request.OpenAppSettings)
            MainAction.OpenNetworkSettings -> request(Request.OpenNetworkSettings)
            MainAction.OpenMetaSettings -> request(Request.OpenMetaSettings)
            MainAction.OpenHelp -> request(Request.OpenHelp)
            is MainAction.OpenSubScreen -> {
                state = state.copy(subScreen = action.screen)

                when (action.screen) {
                    SubScreen.About -> request(Request.LoadAbout)
                    SubScreen.RoutingData -> request(Request.LoadRoutingData)
                }
            }
            MainAction.CloseSubScreen -> state = state.copy(subScreen = null)
            is MainAction.SetAutoCheckUpdate -> {
                state = state.copy(about = state.about.copy(autoCheckUpdate = action.enabled))

                request(Request.SetAutoCheckUpdate(action.enabled))
            }
            is MainAction.SetPrerelease -> {
                state = state.copy(about = state.about.copy(prerelease = action.enabled))

                request(Request.SetPrerelease(action.enabled))
            }
            MainAction.UpdateRoutingData -> request(Request.UpdateRoutingData)
            MainAction.TestDelays -> request(Request.UrlTest)
            is MainAction.ToggleFavorite -> request(Request.ToggleFavorite(action.name))
            is MainAction.SetMode -> request(Request.PatchMode(action.mode))
            is MainAction.OpenUrl -> request(Request.OpenUrl(action.url))
            MainAction.CheckUpdate -> request(Request.CheckUpdate)
            MainAction.UpdateNow -> request(Request.UpdateNow)
            MainAction.UpdateSkip -> request(Request.UpdateSkip)
            MainAction.UpdateLater -> request(Request.UpdateLater)
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

                        if (state.servers.offline) {
                            toast(R.string.clod_select_offline)
                        }
                    }
                }
            }

            is MainAction.SelectTab -> when (action.tab) {
                MainTab.Servers -> {
                    state = state.copy(selectedTab = MainTab.Servers, subScreen = null)
                    request(Request.ReloadProxies)
                }
                MainTab.Home, MainTab.More, MainTab.Subscriptions ->
                    state = state.copy(selectedTab = action.tab, subScreen = null)
            }
        }
    }

    private fun toast(resId: Int) {
        launch { showToast(resId, ToastDuration.Long) }
    }

    suspend fun setNotificationPrompt(show: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(notificationPrompt = show)
        }
    }

    suspend fun setReliability(batteryIgnored: Boolean, alwaysOn: Boolean?, prompt: Boolean? = null) {
        withContext(Dispatchers.Main) {
            state = state.copy(
                reliability = state.reliability.copy(
                    prompt = prompt ?: state.reliability.prompt,
                    batteryIgnored = batteryIgnored,
                    alwaysOn = alwaysOn,
                ),
            )
        }
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

    suspend fun setDisconnecting() {
        withContext(Dispatchers.Main) {
            if (state.status == ConnectionStatus.Connected) {
                state = state.copy(status = ConnectionStatus.Disconnecting)
            }
        }
    }

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
                downloaded = Formatter.formatShortFileSize(context, value.bytesDownload()),
                uploaded = Formatter.formatShortFileSize(context, value.bytesUpload()),
            )
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            state = state.copy(mode = mode)
        }
    }

    val selectedGroup: Int
        get() = state.servers.selected

    val selectedTab: MainTab
        get() = state.selectedTab

    val subScreen: SubScreen?
        get() = state.subScreen

    val selectedSubscriptionGroup: String?
        get() = state.subscriptions.selectedGroup

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

    suspend fun setGroupIcons(icons: Map<String, String>) {
        withContext(Dispatchers.Main) {
            state = state.copy(servers = state.servers.copy(icons = icons))
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

    suspend fun selectTab(tab: MainTab) {
        withContext(Dispatchers.Main) {
            state = state.copy(selectedTab = tab)
        }
    }

    suspend fun setProfiles(profiles: List<SubscriptionItem>) {
        withContext(Dispatchers.Main) {
            val group = state.subscriptions.selectedGroup
                ?.takeIf { selected -> profiles.any { it.group == selected } }

            state = state.copy(
                subscriptions = state.subscriptions.copy(
                    profiles = profiles,
                    selectedGroup = group,
                ),
            )
        }
    }

    val hasProfiles: Boolean
        get() = state.subscriptions.profiles.isNotEmpty()

    suspend fun setUpdatingProfiles(uuids: Set<UUID>) {
        withContext(Dispatchers.Main) {
            state = state.copy(subscriptions = state.subscriptions.copy(updatingUuids = uuids))
        }
    }

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

    suspend fun setUpdateChecking(checking: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(about = state.about.copy(checking = checking))
        }
    }

    suspend fun setRoutingData(files: List<GeoFileState>, providers: List<ProviderFileState>) {
        withContext(Dispatchers.Main) {
            state = state.copy(
                routingData = state.routingData.copy(files = files, providers = providers),
            )
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
