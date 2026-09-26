package com.github.kr328.clash.design.compose.screen

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.ProfileMode
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.toBytesString
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.component.NoServersCard
import com.github.kr328.clash.design.compose.component.PingBadge
import com.github.kr328.clash.design.compose.component.PingBounds
import com.github.kr328.clash.design.compose.component.pingBounds
import com.github.kr328.clash.design.compose.component.PowerButton
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.SelectorRow
import com.github.kr328.clash.design.compose.component.SyncIconButton
import com.github.kr328.clash.design.compose.component.noServersReason
import com.github.kr328.clash.design.compose.component.rememberGroupIcon
import com.github.kr328.clash.design.compose.component.usedTraffic
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.SessionUploadTint
import com.github.kr328.clash.design.compose.theme.statusContainer
import com.github.kr328.clash.design.compose.theme.statusText
import com.github.kr328.clash.design.model.HomeRoute
import com.github.kr328.clash.design.model.ToggleIntent
import com.github.kr328.clash.design.model.homeExtras
import com.github.kr328.clash.design.model.effectiveMode
import com.github.kr328.clash.design.model.parseBannerText
import com.github.kr328.clash.design.model.promoFingerprint
import com.github.kr328.clash.design.model.homeRoute
import com.github.kr328.clash.design.model.providerLinks
import com.github.kr328.clash.design.model.shouldSaveModePick
import com.github.kr328.clash.design.model.toggleIntent
import com.github.kr328.clash.design.util.bidiIsolated
import com.github.kr328.clash.design.util.GroupIcons
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.profileDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

enum class MainTab {
    Home,
    Servers,
    Subscriptions,
    More,
}

enum class SubScreen {
    About,
    RoutingData,
}

@Immutable
data class ProxyGroupState(
    val name: String,
    val now: String,
    val selectable: Boolean,
    val proxies: List<Proxy>,
)

@Immutable
data class ServersState(
    val groups: List<ProxyGroupState> = emptyList(),
    val icons: Map<String, String> = emptyMap(),
    val selected: Int = 0,
    val main: String? = null,
    val allOnHome: Boolean = false,
    val testing: Boolean = false,
    val measuring: Int = 0,
    val offline: Boolean = false,
    val readOnly: Boolean = false,
    val favorites: Set<String> = emptySet(),
)

@Immutable
data class SubscriptionItem(
    val profile: Profile,
    val panel: PanelInfo? = null,
    val group: String? = null,
    val logoPath: String? = null,
) {
    val title: String
        get() = profileDisplayName(panel, profile.name, profile.nameManual)

    val updatable: Boolean
        get() = profile.imported && profile.type != Profile.Type.File

    fun panelNow(): Long = System.currentTimeMillis() + (panel?.clockSkewMillis() ?: 0)
}

@Immutable
data class SubscriptionsState(
    val profiles: List<SubscriptionItem> = emptyList(),
    val updatingUuids: Set<UUID> = emptySet(),
    val selectedGroup: String? = null,
) {
    val updating: Boolean
        get() = updatingUuids.isNotEmpty()
}

@Immutable
data class MainScreenState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val startupStage: String? = null,
    val active: SubscriptionItem? = null,
    val profileMode: ProfileMode = ProfileMode(),
    val selectedTab: MainTab = MainTab.Home,
    val subScreen: SubScreen? = null,
    val servers: ServersState = ServersState(),
    val subscriptions: SubscriptionsState = SubscriptionsState(),
    val about: AboutState = AboutState(),
    val routingData: RoutingDataState = RoutingDataState(),
    val update: UpdateState? = null,
    val notificationPrompt: Boolean = false,
    val reliability: ReliabilityState = ReliabilityState(),
    val restartedBySystem: Boolean = false,
    val systemProxyRefused: Boolean = false,
    val dismissedPromo: String = "",
) {
    val mode: TunnelState.Mode
        get() = effectiveMode(profileMode)
}

@Immutable
data class SessionStats(
    val seconds: Long = 0,
    val downloaded: String = "",
    val uploaded: String = "",
)

sealed interface MainAction {
    data object ToggleStatus : MainAction
    data object OpenAccessControl : MainAction
    data object OpenLogs : MainAction
    data object OpenAppSettings : MainAction
    data object OpenNetworkSettings : MainAction
    data object OpenMetaSettings : MainAction
    data object OpenHelp : MainAction
    data class OpenSubScreen(val screen: SubScreen) : MainAction
    data object CloseSubScreen : MainAction
    data object TestDelays : MainAction
    data class SetMode(val mode: TunnelState.Mode) : MainAction
    data class SelectTab(val tab: MainTab) : MainAction
    data class SelectGroup(val index: Int) : MainAction
    data class OpenGroup(val index: Int) : MainAction
    data class SelectProxy(val name: String) : MainAction
    data class ToggleFavorite(val name: String) : MainAction
    data class DismissPromo(val profile: Profile, val promo: String) : MainAction

    data class OpenUrl(val url: String) : MainAction
    data object CheckUpdate : MainAction
    data object UpdateNow : MainAction
    data object UpdateLater : MainAction
    data object UpdateSkip : MainAction
    data object UpdateCancel : MainAction
    data class SetAutoCheckUpdate(val enabled: Boolean) : MainAction
    data class SetPrerelease(val enabled: Boolean) : MainAction
    data object UpdateRoutingData : MainAction
    data class UpdateRoutingDataProvider(val key: String) : MainAction
    data class SelectSubscriptionGroup(val group: String?) : MainAction
    data class SetSubscriptionGroup(val profile: Profile, val group: String?) : MainAction
    data object NewProfile : MainAction
    data object UpdateAllProfiles : MainAction
    data class ActivateProfile(val profile: Profile) : MainAction
    data class UpdateProfile(val profile: Profile) : MainAction
    data class EditProfile(val profile: Profile) : MainAction
    data class DeleteProfile(val profile: Profile) : MainAction
    data object AllowNotifications : MainAction
    data object SkipNotifications : MainAction
    data object DismissNotifications : MainAction
    data object ReliabilityAllowBattery : MainAction
    data object ReliabilityOpenVpnSettings : MainAction
    data object ReliabilityDismiss : MainAction
}

private const val TAB_TRANSITION_MILLIS = 200

private const val WIDE_LAYOUT_WIDTH_DP = 600

private val CONTENT_MAX_WIDTH = 640.dp

@Composable
private fun NotificationPromptDialog(onAction: (MainAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(MainAction.DismissNotifications) },
        title = { Text(stringResource(R.string.clod_notify_ask_title)) },
        text = { Text(stringResource(R.string.clod_notify_ask_text)) },
        confirmButton = {
            TextButton(onClick = { onAction(MainAction.AllowNotifications) }) {
                Text(stringResource(R.string.clod_notify_ask_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(MainAction.SkipNotifications) }) {
                Text(stringResource(R.string.clod_notify_ask_later))
            }
        },
    )
}

@Composable
fun MainScreen(
    state: MainScreenState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    session: () -> SessionStats = { SessionStats() },
) {
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_LAYOUT_WIDTH_DP

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        bottomBar = {
            if (!wide) {
                MainBottomBar(state.selectedTab, onAction)
            }
        },
    ) { padding ->
        state.update?.let { UpdateDialog(it, onAction) }

        if (state.notificationPrompt) {
            NotificationPromptDialog(onAction)
        }

        if (state.reliability.prompt) {
            ReliabilitySheet(state.reliability, onAction)
        }

        Row(modifier = Modifier.padding(padding)) {
            if (wide) {
                MainNavigationRail(state.selectedTab, onAction)
            }

            MainContent(state, session, onAction)
        }
    }
}

@Composable
private fun MainContent(
    state: MainScreenState,
    session: () -> SessionStats,
    onAction: (MainAction) -> Unit,
) {
    val tabStates = rememberSaveableStateHolder()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxSize(),
        ) {
            when (state.subScreen) {
                SubScreen.About -> AboutScreen(state.about, onAction)
                SubScreen.RoutingData -> RoutingDataScreen(state.routingData, onAction)
                null -> AnimatedContent(
                    targetState = state.selectedTab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val enter = slideInHorizontally(tween(TAB_TRANSITION_MILLIS)) { width ->
                            if (forward) width else -width
                        } + fadeIn(tween(TAB_TRANSITION_MILLIS))
                        val exit = slideOutHorizontally(tween(TAB_TRANSITION_MILLIS)) { width ->
                            if (forward) -width else width
                        } + fadeOut(tween(TAB_TRANSITION_MILLIS))

                        enter togetherWith exit
                    },
                    label = "MainTab",
                ) { tab ->
                    tabStates.SaveableStateProvider(tab.name) {
                        when (tab) {
                            MainTab.Servers -> ServersTab(state.servers, state.active, onAction)
                            MainTab.Subscriptions ->
                                SubscriptionsTab(state.subscriptions, onAction)
                            MainTab.More -> MoreTab(state, onAction)
                            else -> HomeTab(state, session, onAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun isTelevision(): Boolean {
    val uiMode = LocalConfiguration.current.uiMode

    return uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
private fun MainNavigationRail(selected: MainTab, onAction: (MainAction) -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        MainTab.entries.forEach { tab ->
            val (labelRes, iconRes) = tabLabelAndIcon(tab)
            val active = selected == tab

            NavigationRailItem(
                selected = active,
                onClick = { onAction(MainAction.SelectTab(tab)) },
                icon = { TabIcon(iconRes, active) },
                label = {
                    Text(
                        text = stringResource(labelRes),
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = Color.White,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun tabLabelAndIcon(tab: MainTab): Pair<Int, Int> = when (tab) {
    MainTab.Home -> R.string.clod_tab_home to R.drawable.ic_nav_home
    MainTab.Servers -> R.string.clod_tab_servers to R.drawable.ic_nav_servers
    MainTab.Subscriptions -> R.string.clod_tab_subscriptions to R.drawable.ic_baseline_view_list
    MainTab.More -> R.string.clod_tab_more to R.drawable.ic_baseline_settings
}

@Composable
private fun TabIcon(iconRes: Int, active: Boolean) {
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (active) {
                    Modifier.background(ClodTheme.extraColors.brandGradient)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun MainBottomBar(selected: MainTab, onAction: (MainAction) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        MainTab.entries.forEach { tab ->
            val (labelRes, iconRes) = tabLabelAndIcon(tab)
            val active = selected == tab

            NavigationBarItem(
                selected = active,
                onClick = { onAction(MainAction.SelectTab(tab)) },
                icon = { TabIcon(iconRes, active) },
                label = {
                    Text(
                        text = stringResource(labelRes),
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = Color.White,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun HomeTab(
    state: MainScreenState,
    session: () -> SessionStats,
    onAction: (MainAction) -> Unit,
) {
    val powerFocus = remember { FocusRequester() }
    val television = isTelevision()

    LaunchedEffect(television) {
        if (television) {
            withFrameNanos { }

            runCatching { powerFocus.requestFocus() }
                .onFailure { Log.w("Request power focus: $it") }
        }
    }

    val connected = state.status == ConnectionStatus.Connected ||
        state.status == ConnectionStatus.Disconnecting
    val expansion by animateFloatAsState(
        targetValue = if (connected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "homeExpansion",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        MainHeader(
            active = state.active,
            updatingUuids = state.subscriptions.updatingUuids,
            onAction = onAction,
        )

        state.active?.let { active ->
            PanelBanner(active, state.dismissedPromo, onAction)

            ActiveSubscriptionCard(
                item = active,
                showActions = noServersReason(active.profile, active.panel, active.panelNow()) == null,
                onAction = onAction,
            )
        }

        Spacer(Modifier.height((40 - 16 * expansion).dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SessionPowerButton(
                status = state.status,
                intent = toggleIntent(state.status),
                connected = connected,
                session = session,
                onClick = { onAction(MainAction.ToggleStatus) },
                modifier = Modifier.focusRequester(powerFocus),
            )
            Spacer(Modifier.height(16.dp))
            StatusPill(state.status)
            if (!connected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (state.status == ConnectionStatus.Connecting) {
                            startupStageText(state.startupStage)
                        } else {
                            R.string.clod_tap_to_connect
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (connected) {
                SessionTraffic(session)
            }
            if (connected && state.restartedBySystem) {
                SessionNote(stringResource(R.string.clod_session_restarted))
            }
            if (connected && state.systemProxyRefused) {
                SessionNote(stringResource(R.string.clod_session_proxy_refused))
            }
        }

        Spacer(Modifier.height(28.dp))

        state.active?.let { active ->
            AnimatedVisibility(
                visible = !connected,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    QuotaCards(active)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        val route = remember(state.mode, state.servers) {
            homeRoute(state.mode, state.servers.groups, state.servers.main, state.servers.readOnly)
        }

        HomeRouteRow(
            route = route,
            label = stringResource(
                if (connected) {
                    R.string.clod_home_connected_to
                } else {
                    R.string.clod_home_selected_server
                },
            ),
            leading = painterResource(R.drawable.ic_nav_servers),
            groups = state.servers.groups,
            marksOnly = state.active?.panel?.disablePing == true,
            pingBounds = state.active?.panel.pingBounds(),
            onAction = onAction,
        )

        if (state.servers.allOnHome) {
            val extras = remember(state.mode, state.servers) {
                homeExtras(state.mode, state.servers.groups, state.servers.main, state.servers.readOnly)
            }

            extras.forEach { route ->
                val group = when (route) {
                    is HomeRoute.Server -> route.group
                    is HomeRoute.Bypass -> route.group
                    else -> return@forEach
                }
                val icon = rememberGroupIcon(state.servers.icons[group])
                val iconPainter = remember(icon) { icon?.let(::BitmapPainter) }

                Spacer(Modifier.height(8.dp))

                HomeRouteRow(
                    route = route,
                    label = group.bidiIsolated(),
                    leading = iconPainter ?: painterResource(R.drawable.ic_nav_servers),
                    groups = state.servers.groups,
                    marksOnly = state.active?.panel?.disablePing == true,
                    pingBounds = state.active?.panel.pingBounds(),
                    onAction = onAction,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeRouteRow(
    route: HomeRoute,
    label: String,
    leading: Painter,
    groups: List<ProxyGroupState>,
    marksOnly: Boolean,
    pingBounds: PingBounds,
    onAction: (MainAction) -> Unit,
) {
    if (route is HomeRoute.Direct) {
        SelectorRow(
            label = stringResource(R.string.clod_mode),
            value = stringResource(R.string.clod_home_direct),
            leading = painterResource(R.drawable.ic_baseline_vpn_lock),
            onClick = { onAction(MainAction.SelectTab(MainTab.More)) },
        )

        return
    }

    val group = when (route) {
        is HomeRoute.Server -> route.group
        is HomeRoute.Bypass -> route.group
        is HomeRoute.Blocked -> route.group
        HomeRoute.Direct, HomeRoute.None -> return
    }

    val delay = (route as? HomeRoute.Server)?.delay

    SelectorRow(
        label = label,
        value = when (route) {
            is HomeRoute.Server -> (route.title ?: stringResource(R.string.proxy)).bidiIsolated()
            is HomeRoute.Blocked -> stringResource(R.string.clod_home_blocked)
            else -> stringResource(R.string.clod_home_bypass)
        },
        leading = leading,
        onClick = {
            val index = groups.indexOfFirst { it.name == group }

            if (index >= 0) {
                onAction(MainAction.OpenGroup(index))
            } else {
                onAction(MainAction.SelectTab(MainTab.Servers))
            }
        },
        trailing = if (delay != null) {
            {
                PingBadge(
                    delay = delay,
                    on = MaterialTheme.colorScheme.surfaceContainerLow,
                    marksOnly = marksOnly,
                    bounds = pingBounds,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun MainHeader(
    active: SubscriptionItem?,
    updatingUuids: Set<UUID>,
    onAction: (MainAction) -> Unit,
) {
    val profileName = active?.title
    val logoPath = active?.logoPath
    val logoVersion = active?.profile?.updatedAt ?: 0L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val logo = rememberProviderLogo(logoPath, logoVersion)

        if (logo != null) {
            Image(
                bitmap = logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_clash),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profileName ?: stringResource(R.string.application_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active != null) {
                SubscriptionSummary(active)
            }
        }
        if (active != null && active.updatable) {
            SyncIconButton(
                spinning = active.profile.uuid in updatingUuids,
                contentDescription = stringResource(R.string.clod_refresh_profile),
                onClick = { onAction(MainAction.UpdateProfile(active.profile)) },
            )
        }
    }
}

@Composable
private fun SubscriptionSummary(item: SubscriptionItem) {
    val profile = item.profile
    val now = remember(profile) { item.panelNow() }
    val status = subscriptionState(profile, now)
    val used = profile.usedTraffic()

    val label = status.label()
    val daysText = expiryLeft(profile.expire, now)
    val trafficText = when {
        profile.total > 0 -> used.toBytesString() + " / " + profile.total.toBytesString()

        used > 0 -> used.toBytesString()
        else -> null
    }

    val parts = listOfNotNull(label, daysText, trafficText)

    if (parts.size < 2) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(status.color()),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun startupStageText(stage: String?): Int {
    return when (stage) {
        Intents.STAGE_PREPARING -> R.string.clod_stage_preparing
        Intents.STAGE_LOADING -> R.string.clod_stage_loading
        Intents.STAGE_SELECTING -> R.string.clod_stage_selecting
        Intents.STAGE_TUNNEL -> R.string.clod_stage_tunnel
        else -> R.string.clod_stage_starting
    }
}

@Composable
private fun StatusPill(status: ConnectionStatus) {
    val accent = when (status) {
        ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionStatus.Connecting -> ClodTheme.extraColors.statusConnecting
        ConnectionStatus.Connected -> ClodTheme.extraColors.statusConnected
        ConnectionStatus.Disconnecting -> ClodTheme.extraColors.statusConnecting
    }
    val container = if (status == ConnectionStatus.Disconnected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        accent.statusContainer(MaterialTheme.colorScheme.background)
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(accent.statusText()),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = stringResource(
                when (status) {
                    ConnectionStatus.Disconnected -> R.string.clod_status_disconnected
                    ConnectionStatus.Connecting -> R.string.clod_status_connecting
                    ConnectionStatus.Connected -> R.string.clod_status_connected
                    ConnectionStatus.Disconnecting -> R.string.clod_status_disconnecting
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = accent.statusText(),
        )
    }
}

@Composable
private fun SessionPowerButton(
    status: ConnectionStatus,
    intent: ToggleIntent,
    connected: Boolean,
    session: () -> SessionStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seconds = session().seconds

    PowerButton(
        status = status,
        intent = intent,
        onClick = onClick,
        modifier = modifier,
        caption = formatSession(seconds).takeIf { connected && seconds > 0 },
    )
}

@Composable
private fun SessionNote(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun SessionTraffic(session: () -> SessionStats) {
    val stats = session()

    if (stats.downloaded.isNotBlank() && stats.uploaded.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        SessionTrafficRow(downloaded = stats.downloaded, uploaded = stats.uploaded)
    }
}

@Composable
private fun SessionTrafficRow(downloaded: String, uploaded: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_traffic_down),
            contentDescription = stringResource(R.string.clod_traffic_downloaded),
            tint = ClodTheme.extraColors.statusConnected,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = downloaded,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Icon(
            painter = painterResource(R.drawable.ic_traffic_up),
            contentDescription = stringResource(R.string.clod_traffic_uploaded),
            tint = SessionUploadTint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = uploaded,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuotaCards(item: SubscriptionItem) {
    val profile = item.profile
    val now = remember(profile) { item.panelNow() }

    if (subscriptionState(profile, now) != SubscriptionState.Active) return
    if (profile.total <= 0L && profile.expire <= 0L) return

    val used = profile.usedTraffic()

    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (profile.total > 0) {
            val left = (profile.total - used).coerceAtLeast(0)

            QuotaCard(
                label = stringResource(R.string.clod_quota_traffic),
                value = used.toBytesString() + " / " + profile.total.toBytesString(),
                progress = (used.toFloat() / profile.total).coerceIn(0f, 1f),
                note = stringResource(
                    R.string.clod_quota_left,
                    left.toBytesString(),
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        if (profile.expire > 0) {
            QuotaCard(
                label = stringResource(R.string.clod_quota_expiry),
                value = expiryLeft(profile.expire, now)
                    ?: stringResource(R.string.clod_sub_expired),
                progress = null,
                note = expiryDate(profile.expire, now),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun QuotaCard(
    label: String,
    value: String,
    progress: Float?,
    note: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50)),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatSession(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

private const val COLLAPSED_NOTICE_LINES = 6

private const val COLLAPSED_PROMO_LINES = 2

@Composable
private fun PanelBanner(active: SubscriptionItem, dismissedPromo: String, onAction: (MainAction) -> Unit) {
    val panel = active.panel ?: return
    val fingerprint = remember(panel.promo) { promoFingerprint(panel.promo) }
    val promo = panel.promo.takeIf { it.isNotBlank() && fingerprint != dismissedPromo }.orEmpty()
    val reason = noServersReason(active.profile, panel, active.panelNow())

    if (panel.announce.isBlank() && promo.isBlank() && reason == null) return

    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        if (reason != null) {
            NoServersCard(
                reason = reason,
                panel = panel,
                profile = active.profile,
                onOpenUrl = { onAction(MainAction.OpenUrl(it)) },
                onOpenSettings = { onAction(MainAction.OpenAppSettings) },
            )

            Spacer(Modifier.height(8.dp))
        }

        if (panel.announce.isNotBlank()) {
            NoticeCard(
                text = panel.announce,
                url = panel.announceUrl,
                collapsedLines = COLLAPSED_NOTICE_LINES,
                onAction = onAction,
            )
        }

        if (promo.isNotBlank()) {
            if (panel.announce.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
            }

            NoticeCard(
                text = promo,
                url = panel.promoUrl,
                collapsedLines = COLLAPSED_PROMO_LINES,
                onAction = onAction,
                onDismiss = { onAction(MainAction.DismissPromo(active.profile, promo)) },
            )
        }
    }
}

@Composable
private fun NoticeCard(
    text: String,
    url: String,
    collapsedLines: Int,
    onAction: (MainAction) -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var truncated by rememberSaveable(text) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "noticeChevron",
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (url.isNotBlank()) {
                        Modifier.clickable { onAction(MainAction.OpenUrl(url)) }
                    } else {
                        Modifier
                    },
                )
                .padding(14.dp)
                .animateContentSize(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_outline_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = bannerAnnotated(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = {
                    if (!expanded) {
                        truncated = it.hasVisualOverflow
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (truncated) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { expanded = !expanded },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = stringResource(
                            if (expanded) {
                                R.string.clod_notice_collapse
                            } else {
                                R.string.clod_notice_expand
                            },
                        ),
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(rotation),
                    )
                }
            }
            if (onDismiss != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.close),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Провайдерский текст с подсветкой слов (`#RRGGBB` вплотную к слову) — как
 * `BannerText` на ПК: покрашенный кусок берёт цвет как прислали и полужирный,
 * непокрашенный наследует цвет карточки.
 */
private fun bannerAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    for (fragment in parseBannerText(text)) {
        val color = fragment.color
        if (color == null) {
            append(fragment.text)
        } else {
            withStyle(
                SpanStyle(
                    color = Color(0xFF000000L or color.substring(1).toLong(16)),
                    fontWeight = FontWeight.SemiBold,
                ),
            ) {
                append(fragment.text)
            }
        }
    }
}

@Composable
fun modeLabel(mode: TunnelState.Mode): String = stringResource(
    when (mode) {
        TunnelState.Mode.Direct -> R.string.direct_mode
        TunnelState.Mode.Global -> R.string.global_mode
        else -> R.string.rule_mode
    },
)

@Composable
private fun ModeRow(mode: ProfileMode, onAction: (MainAction) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }

    val acting = effectiveMode(mode)
    val label = modeLabel(acting)
    val locked = mode.source == ProfileMode.Source.Locked

    ActionRow(
        title = stringResource(R.string.clod_mode),
        subtitle = when {
            locked -> stringResource(R.string.clod_mode_locked, label)
            mode.source == ProfileMode.Source.Choice -> stringResource(R.string.clod_mode_chosen, label)
            mode.source == ProfileMode.Source.Override -> stringResource(R.string.clod_mode_from_override, label)
            else -> stringResource(R.string.clod_mode_from_subscription, label)
        },
        icon = painterResource(R.drawable.ic_baseline_vpn_lock),
        onClick = { picking = true },
    )

    if (picking && locked) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.clod_mode)) },
            text = { Text(stringResource(R.string.clod_mode_locked_hint)) },
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )

        return
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.clod_mode)) },
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = {
                Column(Modifier.selectableGroup()) {
                    listOf(
                        TunnelState.Mode.Rule,
                        TunnelState.Mode.Global,
                        TunnelState.Mode.Direct,
                    ).forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = candidate == acting, role = Role.RadioButton) {
                                    picking = false
                                    if (shouldSaveModePick(mode, candidate)) {
                                        onAction(MainAction.SetMode(candidate))
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = candidate == acting, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(modeLabel(candidate))
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ProviderLinksSection(active: SubscriptionItem?, onAction: (MainAction) -> Unit) {
    val links = providerLinks(active?.panel)

    if (links.isEmpty() || active == null) return

    SectionHeader(active.title)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        links.forEachIndexed { index, link ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 52.dp),
                )
            }

            ActionRow(
                title = stringResource(link.title),
                icon = painterResource(link.icon),
                onClick = { onAction(MainAction.OpenUrl(link.url)) },
            )
        }
    }
}

@Composable
private fun MoreTab(state: MainScreenState, onAction: (MainAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.clod_tab_more),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 18.dp, top = 20.dp, bottom = 12.dp),
            )

            ProviderLinksSection(state.active, onAction)

            SectionHeader(stringResource(R.string.clod_section_connection))
            if (state.active != null) {
                ModeRow(
                    mode = state.profileMode,
                    onAction = onAction,
                )
            }
            ActionRow(
                title = stringResource(R.string.clod_apps),
                subtitle = stringResource(R.string.clod_apps_subtitle),
                icon = painterResource(R.drawable.ic_baseline_apps),
                onClick = { onAction(MainAction.OpenAccessControl) },
            )
            ReliabilityRows(state.reliability, onAction)
            ActionRow(
                title = stringResource(R.string.network),
                subtitle = stringResource(R.string.clod_settings_network_subtitle),
                icon = painterResource(R.drawable.ic_baseline_dns),
                onClick = { onAction(MainAction.OpenNetworkSettings) },
            )

            SectionHeader(stringResource(R.string.clod_section_settings))
            ActionRow(
                title = stringResource(R.string.clod_settings_app_title),
                subtitle = stringResource(R.string.clod_settings_app_subtitle),
                icon = painterResource(R.drawable.ic_baseline_settings),
                onClick = { onAction(MainAction.OpenAppSettings) },
            )
            ActionRow(
                title = stringResource(R.string.meta_features),
                subtitle = stringResource(R.string.clod_settings_meta_subtitle),
                icon = painterResource(R.drawable.ic_baseline_meta),
                onClick = { onAction(MainAction.OpenMetaSettings) },
            )
            ActionRow(
                title = stringResource(R.string.clod_data_title),
                subtitle = stringResource(R.string.clod_geo_subtitle),
                icon = painterResource(R.drawable.ic_baseline_domain),
                onClick = { onAction(MainAction.OpenSubScreen(SubScreen.RoutingData)) },
            )

            SectionHeader(stringResource(R.string.clod_section_support))
            ActionRow(
                title = stringResource(R.string.help),
                icon = painterResource(R.drawable.ic_baseline_help_center),
                onClick = { onAction(MainAction.OpenHelp) },
            )
            ActionRow(
                title = stringResource(R.string.logs),
                icon = painterResource(R.drawable.ic_baseline_assignment),
                onClick = { onAction(MainAction.OpenLogs) },
            )
            ActionRow(
                title = stringResource(R.string.about),
                subtitle = state.about.versionName
                    .takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.clod_about_subtitle, it) }
                    ?: stringResource(R.string.clod_update_check),
                icon = painterResource(R.drawable.ic_baseline_info),
                onClick = { onAction(MainAction.OpenSubScreen(SubScreen.About)) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun rememberProviderLogo(path: String?, version: Long): ImageBitmap? {
    val target = path?.takeIf { it.isNotBlank() }

    return produceState<ImageBitmap?>(initialValue = null, target, version) {
        value = target?.let {
            withContext(Dispatchers.IO) { GroupIcons.loadLocal(File(it)) }
        }
    }.value
}
