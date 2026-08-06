package com.github.kr328.clash.design.compose.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.component.PowerButton
import com.github.kr328.clash.design.compose.component.SelectorRow
import com.github.kr328.clash.design.compose.component.TrafficCard
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.StatusTextStyle
import com.github.kr328.clash.service.model.Profile

/** Вкладки нижней навигации. Порядок совпадает с утверждённым макетом. */
enum class MainTab {
    Home,
    Servers,
    Subscriptions,
    More,
}

/**
 * Одна группа прокси в том виде, в каком её показывает вкладка «Серверы».
 *
 * @param selectable группа типа Selector — узел в ней можно выбрать руками.
 *   В url-test и fallback узел выбирает ядро, и патч селектора там не сработает.
 */
data class ProxyGroupState(
    val name: String,
    val now: String,
    val selectable: Boolean,
    val proxies: List<Proxy>,
)

/** Состояние вкладки «Серверы». */
data class ServersState(
    val groups: List<ProxyGroupState> = emptyList(),
    val selected: Int = 0,
    val testing: Boolean = false,
)

/** Состояние вкладки «Подписки». */
data class SubscriptionsState(
    val profiles: List<Profile> = emptyList(),
    val updating: Boolean = false,
)

/**
 * Всё, что показывает главный экран. Отдельный неизменяемый снимок вместо
 * россыпи параметров: экран перерисовывается одним `setState`, и ни один
 * промежуточный кадр не может застать половину полей обновлёнными.
 */
data class MainScreenState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val profileName: String? = null,
    val mode: TunnelState.Mode = TunnelState.Mode.Rule,
    val downloaded: String = "",
    val uploaded: String = "",
    val hasProviders: Boolean = false,
    val selectedTab: MainTab = MainTab.Home,
    val servers: ServersState = ServersState(),
    val subscriptions: SubscriptionsState = SubscriptionsState(),
)

/** Действия пользователя. Экран сам ничего не делает — только сообщает наверх. */
sealed interface MainAction {
    data object ToggleStatus : MainAction
    data object OpenProviders : MainAction
    data object OpenAccessControl : MainAction
    data object OpenLogs : MainAction
    data object OpenSettings : MainAction
    data object OpenHelp : MainAction
    data object OpenAbout : MainAction
    data object TestDelays : MainAction
    data class SetMode(val mode: TunnelState.Mode) : MainAction
    data class SelectTab(val tab: MainTab) : MainAction
    data class SelectGroup(val index: Int) : MainAction
    data class SelectProxy(val name: String) : MainAction

    data object NewProfile : MainAction
    data object UpdateAllProfiles : MainAction
    data class ActivateProfile(val profile: Profile) : MainAction
    data class UpdateProfile(val profile: Profile) : MainAction
    data class EditProfile(val profile: Profile) : MainAction
    data class DeleteProfile(val profile: Profile) : MainAction
}

@Composable
fun MainScreen(
    state: MainScreenState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MainBottomBar(state.selectedTab, onAction) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (state.selectedTab) {
                MainTab.Servers -> ServersTab(state.servers, onAction)
                MainTab.Subscriptions -> SubscriptionsTab(state.subscriptions, onAction)
                MainTab.More -> MoreTab(state, onAction)
                else -> HomeTab(state, onAction)
            }
        }
    }
}

@Composable
private fun MainBottomBar(selected: MainTab, onAction: (MainAction) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        MainTab.entries.forEach { tab ->
            val (labelRes, iconRes) = when (tab) {
                MainTab.Home -> R.string.clod_tab_home to R.drawable.ic_nav_home
                MainTab.Servers -> R.string.clod_tab_servers to R.drawable.ic_nav_servers
                MainTab.Subscriptions ->
                    R.string.clod_tab_subscriptions to R.drawable.ic_baseline_view_list

                MainTab.More -> R.string.clod_tab_more to R.drawable.ic_baseline_settings
            }
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onAction(MainAction.SelectTab(tab)) },
                icon = {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun HomeTab(state: MainScreenState, onAction: (MainAction) -> Unit) {
    val connected = state.status == ConnectionStatus.Connected
    // Одна анимируемая величина на всё раскрытие экрана: карточки, размер кнопки
    // и отступы двигаются синхронно, потому что читают её же.
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
        MainHeader(state.profileName, onAction)

        AnimatedVisibility(
            visible = connected,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TrafficCard(
                    label = stringResource(R.string.clod_traffic_downloaded),
                    value = state.downloaded,
                    icon = painterResource(R.drawable.ic_traffic_down),
                    modifier = Modifier.weight(1f),
                )
                TrafficCard(
                    label = stringResource(R.string.clod_traffic_uploaded),
                    value = state.uploaded,
                    icon = painterResource(R.drawable.ic_traffic_up),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height((40 - 16 * expansion).dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PowerButton(
                status = state.status,
                onClick = { onAction(MainAction.ToggleStatus) },
                diameter = (148 - 28 * expansion).dp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(
                    when (state.status) {
                        ConnectionStatus.Disconnected -> R.string.clod_status_disconnected
                        ConnectionStatus.Connecting -> R.string.clod_status_connecting
                        ConnectionStatus.Connected -> R.string.clod_status_connected
                    },
                ),
                style = StatusTextStyle,
                color = when (state.status) {
                    ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurface
                    ConnectionStatus.Connecting -> ClodTheme.extraColors.statusConnecting
                    ConnectionStatus.Connected -> ClodTheme.extraColors.statusConnected
                },
                textAlign = TextAlign.Center,
            )
            if (!connected) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.clod_tap_to_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        SelectorRow(
            label = stringResource(R.string.clod_selector_group),
            value = state.servers.groups.getOrNull(state.servers.selected)?.now
                ?: stringResource(R.string.proxy),
            leading = painterResource(R.drawable.ic_nav_servers),
            onClick = { onAction(MainAction.SelectTab(MainTab.Servers)) },
        )
        Spacer(Modifier.height(10.dp))
        SelectorRow(
            label = stringResource(R.string.clod_tab_subscriptions),
            value = state.profileName ?: stringResource(R.string.clod_no_subscription),
            leading = painterResource(R.drawable.ic_baseline_view_list),
            onClick = { onAction(MainAction.SelectTab(MainTab.Subscriptions)) },
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MainHeader(profileName: String?, onAction: (MainAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_clash),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = profileName ?: stringResource(R.string.application_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onAction(MainAction.UpdateAllProfiles) }) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_sync),
                contentDescription = stringResource(R.string.clod_refresh_profile),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Вкладка «Ещё». Собирает то, что на десктопе живёт в боковом меню, а у CMFA
 * лежало прямо на главном экране вперемешку с кнопкой подключения.
 */
/** Заголовок группы пунктов во вкладке «Ещё». */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Подпись режима туннеля. Ключи строк те же, что у XML-слоя. */
@Composable
fun modeLabel(mode: TunnelState.Mode): String = stringResource(
    when (mode) {
        TunnelState.Mode.Direct -> R.string.direct_mode
        TunnelState.Mode.Global -> R.string.global_mode
        else -> R.string.rule_mode
    },
)

/**
 * Выбор режима туннеля. У CMFA он прятался в меню с тремя точками на экране
 * прокси; в макете это отдельный пункт в «Ещё», где его и ищут.
 *
 * Script в списке нет намеренно: ядро его больше не поддерживает, а показывать
 * пункт, который не применится, — врать пользователю.
 */
@Composable
private fun ModeRow(mode: TunnelState.Mode, onAction: (MainAction) -> Unit) {
    var picking by remember { mutableStateOf(false) }

    ActionRow(
        title = stringResource(R.string.clod_mode),
        subtitle = modeLabel(mode),
        icon = painterResource(R.drawable.ic_baseline_vpn_lock),
        onClick = { picking = true },
    )

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
            SectionHeader(stringResource(R.string.clod_section_connection))
            ModeRow(state.mode, onAction)
            ActionRow(
                title = stringResource(R.string.clod_apps),
                subtitle = stringResource(R.string.clod_apps_subtitle),
                icon = painterResource(R.drawable.ic_baseline_apps),
                onClick = { onAction(MainAction.OpenAccessControl) },
            )
            if (state.hasProviders) {
                ActionRow(
                    title = stringResource(R.string.providers),
                    icon = painterResource(R.drawable.ic_baseline_swap_vertical_circle),
                    onClick = { onAction(MainAction.OpenProviders) },
                )
            }

            SectionHeader(stringResource(R.string.clod_section_app))
            ActionRow(
                title = stringResource(R.string.logs),
                icon = painterResource(R.drawable.ic_baseline_assignment),
                onClick = { onAction(MainAction.OpenLogs) },
            )
            ActionRow(
                title = stringResource(R.string.settings),
                icon = painterResource(R.drawable.ic_baseline_settings),
                onClick = { onAction(MainAction.OpenSettings) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ActionRow(
                title = stringResource(R.string.help),
                icon = painterResource(R.drawable.ic_baseline_help_center),
                onClick = { onAction(MainAction.OpenHelp) },
            )
            ActionRow(
                title = stringResource(R.string.about),
                icon = painterResource(R.drawable.ic_baseline_info),
                onClick = { onAction(MainAction.OpenAbout) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
