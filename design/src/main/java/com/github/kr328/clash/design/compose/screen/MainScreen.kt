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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.component.PowerButton
import com.github.kr328.clash.design.compose.component.SelectorRow
import com.github.kr328.clash.design.compose.component.TrafficCard
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.StatusTextStyle

/** Вкладки нижней навигации. Порядок совпадает с утверждённым макетом. */
enum class MainTab {
    Home,
    Servers,
    Subscriptions,
    More,
}

/**
 * Всё, что показывает главный экран. Отдельный неизменяемый снимок вместо
 * россыпи параметров: экран перерисовывается одним `setState`, и ни один
 * промежуточный кадр не может застать половину полей обновлёнными.
 */
data class MainScreenState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val profileName: String? = null,
    val mode: String = "",
    val downloaded: String = "",
    val uploaded: String = "",
    val hasProviders: Boolean = false,
    val selectedTab: MainTab = MainTab.Home,
)

/** Действия пользователя. Экран сам ничего не делает — только сообщает наверх. */
sealed interface MainAction {
    data object ToggleStatus : MainAction
    data object OpenProxy : MainAction
    data object OpenProfiles : MainAction
    data object OpenProviders : MainAction
    data object OpenLogs : MainAction
    data object OpenSettings : MainAction
    data object OpenHelp : MainAction
    data object OpenAbout : MainAction
    data class SelectTab(val tab: MainTab) : MainAction
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
            value = state.mode.ifEmpty { stringResource(R.string.proxy) },
            leading = painterResource(R.drawable.ic_nav_servers),
            onClick = { onAction(MainAction.OpenProxy) },
        )
        Spacer(Modifier.height(10.dp))
        SelectorRow(
            label = stringResource(R.string.clod_tab_subscriptions),
            value = state.profileName ?: stringResource(R.string.clod_no_subscription),
            leading = painterResource(R.drawable.ic_baseline_view_list),
            onClick = { onAction(MainAction.OpenProfiles) },
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
        IconButton(onClick = { onAction(MainAction.OpenProfiles) }) {
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
            if (state.hasProviders) {
                ActionRow(
                    title = stringResource(R.string.providers),
                    icon = painterResource(R.drawable.ic_baseline_swap_vertical_circle),
                    onClick = { onAction(MainAction.OpenProviders) },
                )
            }
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
