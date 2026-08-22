package com.github.kr328.clash.design.compose.screen

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.component.NoServersCard
import com.github.kr328.clash.design.compose.component.PingBadge
import com.github.kr328.clash.design.compose.component.PowerButton
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.SelectorRow
import com.github.kr328.clash.design.compose.component.SyncIconButton
import com.github.kr328.clash.design.compose.component.noServersReason
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.SessionUploadTint
import com.github.kr328.clash.design.model.providerLinks
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.model.Profile
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    val testing: Boolean = false,
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
        get() = panel?.title?.takeIf { it.isNotBlank() } ?: profile.name

    fun panelClockSkew(): Long = panel?.clockSkewMillis() ?: 0
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
    val active: SubscriptionItem? = null,
    val mode: TunnelState.Mode = TunnelState.Mode.Rule,
    val downloaded: String = "",
    val uploaded: String = "",
    val sessionSeconds: Long = 0,
    val selectedTab: MainTab = MainTab.Home,
    val subScreen: SubScreen? = null,
    val servers: ServersState = ServersState(),
    val subscriptions: SubscriptionsState = SubscriptionsState(),
    val about: AboutState = AboutState(),
    val routingData: RoutingDataState = RoutingDataState(),
    val update: UpdateState? = null,
    val notificationPrompt: Boolean = false,
    val reliability: ReliabilityState = ReliabilityState(),
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
    data class SelectProxy(val name: String) : MainAction
    data class ToggleFavorite(val name: String) : MainAction

    data class OpenUrl(val url: String) : MainAction
    data object CheckUpdate : MainAction
    data object UpdateNow : MainAction
    data object UpdateLater : MainAction
    data object UpdateSkip : MainAction
    data class SetAutoCheckUpdate(val enabled: Boolean) : MainAction
    data class SetPrerelease(val enabled: Boolean) : MainAction
    data object UpdateRoutingData : MainAction
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
    data object ReliabilityConnect : MainAction
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

            MainContent(state, onAction)
        }
    }
}

@Composable
private fun MainContent(state: MainScreenState, onAction: (MainAction) -> Unit) {
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
                    when (tab) {
                        MainTab.Servers -> ServersTab(state.servers, state.active, onAction)
                        MainTab.Subscriptions -> SubscriptionsTab(state.subscriptions, onAction)
                        MainTab.More -> MoreTab(state, onAction)
                        else -> HomeTab(state, onAction)
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
private fun HomeTab(state: MainScreenState, onAction: (MainAction) -> Unit) {
    val powerFocus = remember { FocusRequester() }
    val television = isTelevision()

    LaunchedEffect(television) {
        if (television) {
            withFrameNanos { }

            runCatching { powerFocus.requestFocus() }
                .onFailure { Log.w("Request power focus: $it") }
        }
    }

    val connected = state.status == ConnectionStatus.Connected
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
            updating = state.subscriptions.updating,
            onAction = onAction,
        )

        state.active?.let { active ->
            PanelBanner(active, onAction)

            ActiveSubscriptionCard(
                item = active,
                showActions = noServersReason(active.profile, active.panel) == null,
                onAction = onAction,
            )
        }

        Spacer(Modifier.height((40 - 16 * expansion).dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PowerButton(
                status = state.status,
                onClick = { onAction(MainAction.ToggleStatus) },
                modifier = Modifier.focusRequester(powerFocus),
                diameter = 134.dp,
                caption = formatSession(state.sessionSeconds).takeIf {
                    connected && state.sessionSeconds > 0
                },
            )
            Spacer(Modifier.height(16.dp))
            StatusPill(state.status)
            if (!connected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.clod_tap_to_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (connected && state.downloaded.isNotBlank() && state.uploaded.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                SessionTrafficRow(downloaded = state.downloaded, uploaded = state.uploaded)
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

        state.servers.groups.getOrNull(state.servers.selected)?.let { group ->
            val current = group.proxies.firstOrNull { it.name == group.now }

            SelectorRow(
                label = stringResource(
                    if (connected) {
                        R.string.clod_home_connected_to
                    } else {
                        R.string.clod_home_selected_server
                    },
                ),
                value = current?.title
                    ?: group.now.ifBlank { stringResource(R.string.proxy) },
                leading = painterResource(R.drawable.ic_nav_servers),
                onClick = { onAction(MainAction.SelectTab(MainTab.Servers)) },
                trailing = if (current != null) {
                    { PingBadge(current.delay, marksOnly = state.active?.panel?.disablePing == true) }
                } else {
                    null
                },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MainHeader(
    active: SubscriptionItem?,
    updating: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val profileName = active?.title
    val logoPath = active?.logoPath

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val logo = rememberProviderLogo(logoPath)

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
        SyncIconButton(
            spinning = updating,
            contentDescription = stringResource(R.string.clod_refresh_profile),
            onClick = { onAction(MainAction.UpdateAllProfiles) },
        )
    }
}

@Composable
private fun SubscriptionSummary(item: SubscriptionItem) {
    val context = LocalContext.current
    val profile = item.profile
    val now = remember(profile) { System.currentTimeMillis() + item.panelClockSkew() }
    val status = subscriptionState(profile, now)
    val used = profile.upload + profile.download

    val label = status.label()
    val days = if (profile.expire > 0) {
        ((profile.expire - now) / TimeUnit.DAYS.toMillis(1)).toInt()
    } else {
        -1
    }
    val daysText = if (days >= 0) stringResource(R.string.clod_sub_days, days) else null
    val trafficText = when {
        profile.total > 0 -> Formatter.formatShortFileSize(context, used) + " / " +
            Formatter.formatShortFileSize(context, profile.total)

        used > 0 -> Formatter.formatShortFileSize(context, used)
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

@Composable
private fun StatusPill(status: ConnectionStatus) {
    val accent = when (status) {
        ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionStatus.Connecting -> ClodTheme.extraColors.statusConnecting
        ConnectionStatus.Connected -> ClodTheme.extraColors.statusConnected
    }
    val container = if (status == ConnectionStatus.Disconnected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        accent.copy(alpha = 0.14f)
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
                .background(accent),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = stringResource(
                when (status) {
                    ConnectionStatus.Disconnected -> R.string.clod_status_disconnected
                    ConnectionStatus.Connecting -> R.string.clod_status_connecting
                    ConnectionStatus.Connected -> R.string.clod_status_connected
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = accent,
        )
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
    val now = remember(profile) { System.currentTimeMillis() + item.panelClockSkew() }

    if (subscriptionState(profile, now) != SubscriptionState.Active) return
    if (profile.total <= 0L && profile.expire <= 0L) return

    val context = LocalContext.current
    val used = profile.upload + profile.download

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (profile.total > 0) {
            val left = (profile.total - used).coerceAtLeast(0)

            QuotaCard(
                label = stringResource(R.string.clod_quota_traffic),
                value = Formatter.formatShortFileSize(context, used) + " / " +
                    Formatter.formatShortFileSize(context, profile.total),
                progress = (used.toFloat() / profile.total).coerceIn(0f, 1f),
                note = stringResource(
                    R.string.clod_quota_left,
                    Formatter.formatShortFileSize(context, left),
                ),
                modifier = Modifier.weight(1f),
            )
        }
        if (profile.expire > 0) {
            val leftMillis = (profile.expire - now).coerceAtLeast(0)
            val days = (leftMillis / TimeUnit.DAYS.toMillis(1)).toInt()

            QuotaCard(
                label = stringResource(R.string.clod_quota_expiry),
                value = stringResource(R.string.clod_sub_days, days),
                progress = (leftMillis.toFloat() / TimeUnit.DAYS.toMillis(30)).coerceIn(0f, 1f),
                note = stringResource(
                    R.string.clod_sub_until,
                    android.text.format.DateFormat.getDateFormat(context)
                        .format(java.util.Date(profile.expire)),
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuotaCard(
    label: String,
    value: String,
    progress: Float,
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
                text = label.uppercase(),
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
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50)),
            )
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

@Composable
private fun PanelBanner(active: SubscriptionItem, onAction: (MainAction) -> Unit) {
    val panel = active.panel ?: return
    val notice = panel.announce.takeIf { it.isNotBlank() } ?: panel.promo
    val noticeUrl = if (panel.announce.isNotBlank()) panel.announceUrl else panel.promoUrl
    val reason = noServersReason(active.profile, panel)

    if (notice.isBlank() && reason == null) return

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

        if (notice.isNotBlank()) {
            var expanded by remember(notice) { mutableStateOf(false) }
            var truncated by remember(notice) { mutableStateOf(false) }
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
                            if (noticeUrl.isNotBlank()) {
                                Modifier.clickable { onAction(MainAction.OpenUrl(noticeUrl)) }
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
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_NOTICE_LINES,
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
                            modifier = Modifier.size(32.dp),
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
                }
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
private fun ModeRow(mode: TunnelState.Mode, locked: Boolean, onAction: (MainAction) -> Unit) {
    var picking by remember { mutableStateOf(false) }

    ActionRow(
        title = stringResource(R.string.clod_mode),
        subtitle = if (locked) {
            stringResource(R.string.clod_mode_locked, modeLabel(mode))
        } else {
            modeLabel(mode)
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
                    Text(stringResource(android.R.string.ok))
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
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = {
                Column {
                    listOf(
                        TunnelState.Mode.Rule,
                        TunnelState.Mode.Global,
                        TunnelState.Mode.Direct,
                    ).forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picking = false
                                    onAction(MainAction.SetMode(candidate))
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = candidate == mode, onClick = null)
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
            ModeRow(
                mode = state.mode,
                locked = state.active?.panel?.lockMode == true,
                onAction = onAction,
            )
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
private fun rememberProviderLogo(path: String?): ImageBitmap? = remember(path) {
    if (path.isNullOrBlank()) return@remember null

    runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
}
