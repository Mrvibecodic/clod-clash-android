package com.github.kr328.clash.design.compose.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.bidiIsolated
import com.github.kr328.clash.design.util.measuringMinutes
import com.github.kr328.clash.design.compose.component.NoServersCard
import com.github.kr328.clash.design.compose.component.ProxyRow
import com.github.kr328.clash.design.compose.component.chipStripScroll
import com.github.kr328.clash.design.compose.component.pingBounds
import com.github.kr328.clash.design.compose.component.noServersReason
import com.github.kr328.clash.design.compose.component.GroupIcon
import com.github.kr328.clash.design.compose.component.SelectorRow
import com.github.kr328.clash.design.compose.component.ToolbarSpinner
import com.github.kr328.clash.design.compose.component.rememberGroupIcon
import com.github.kr328.clash.design.model.BUILTIN_OUTBOUNDS
import com.github.kr328.clash.design.model.proxySubtitle

@Composable
fun ServersTab(
    state: ServersState,
    active: SubscriptionItem?,
    onAction: (MainAction) -> Unit,
    loaded: Boolean,
) {
    val noServers = active?.let { noServersReason(it.profile, it.panel, it.panelNow()) }

    val descriptions = active?.panel?.descriptions.orEmpty()
    val protocols = active?.panel?.protocols.orEmpty()
    // Подпись протокола прячет подписка, чьи серверы показаны (clod-hide-badges)
    val hideBadges = active?.panel?.hideBadges == true
    val mobileOnly = active?.panel?.mobileOnly.orEmpty().toSet()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.clod_tab_servers),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Серверы — без вложенных групп и встроенных DIRECT/REJECT
                val count = state.groups.getOrNull(state.selected)?.proxies.orEmpty().count { proxy ->
                    !proxy.isGroup && proxy.name !in BUILTIN_OUTBOUNDS && state.groups.none { it.name == proxy.name }
                }

                if (state.testing && state.measuring > 0) {
                    Text(
                        text = if (state.measured > 0) {
                            stringResource(R.string.clod_delays_measured, state.measured, state.measuring)
                        } else {
                            pluralStringResource(
                                R.plurals.clod_delays_measuring,
                                state.measuring,
                                state.measuring,
                                measuringMinutes(state.measuring),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (count > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.clod_nodes_count, count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Во время замера кнопка остаётся на месте (крутилка внутри неё) и
            // держит фокус: на ТВ пульт иначе теряет его
            IconButton(
                onClick = { if (!state.testing) onAction(MainAction.TestDelays) },
                enabled = state.groups.isNotEmpty() && !state.readOnly && noServers == null,
            ) {
                if (state.testing) {
                    ToolbarSpinner(size = 24.dp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_flash_on),
                        contentDescription = stringResource(R.string.clod_test_delays),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (noServers != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                NoServersCard(
                    reason = noServers,
                    panel = active?.panel,
                    profile = active?.profile,
                    onOpenUrl = { onAction(MainAction.OpenUrl(it)) },
                    onOpenSettings = { onAction(MainAction.OpenAppSettings) },
                )
            }

            return@Column
        }

        if (state.groups.isEmpty()) {
            if (loaded) {
                EmptyServers(
                    when {
                        state.failed -> R.string.clod_no_servers_query_failed
                        active != null -> R.string.clod_no_servers_in_subscription
                        else -> R.string.clod_no_servers_hint
                    },
                )
            } else {
                Waiting()
            }
            return@Column
        }

        GroupSelector(state = state, onAction = onAction)

        if (state.offline) {
            Text(
                text = stringResource(
                    when {
                        state.readOnly -> R.string.clod_servers_direct
                        state.loading -> R.string.clod_servers_loading
                        else -> R.string.clod_servers_offline
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }

        val group = state.groups.getOrNull(state.selected)
        if (group?.allHidden == true) {
            Text(
                text = stringResource(R.string.clod_servers_mobile_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            )
            return@Column
        }
        if (group == null || group.proxies.isEmpty()) {
            if (group?.read == false) Waiting() else EmptyServers(R.string.clod_no_servers_in_group)
            return@Column
        }

        val proxies = remember(group.proxies, state.favorites) {
            if (state.favorites.isEmpty()) {
                group.proxies
            } else {
                group.proxies.sortedByDescending { it.name in state.favorites }
            }
        }

        key(state.selected) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Без ключа по имени: ядро допускает одноимённые узлы из разных провайдеров,
                // а одинаковый ключ роняет список
                items(items = proxies) { proxy ->
                    ProxyRow(
                        title = proxy.title,
                        subtitle = proxySubtitle(
                            proxy,
                            descriptions[proxy.name],
                            protocols[proxy.name],
                            hideBadges,
                            // Заглушки панели в списке нет — и в подписи группы тоже
                            current = proxy.current.takeIf { it.isNotEmpty() && active?.panel?.hides(it) != true }
                                ?.let { stringResource(R.string.clod_proxy_now, proxy.currentTitle.ifEmpty { it }.bidiIsolated()) },
                        ),
                        delay = proxy.delay,
                        marksOnly = active?.panel?.disablePing == true,
                        pingBounds = active?.panel.pingBounds(),
                        selected = proxy.name == group.now,
                        pinned = proxy.name == group.pinned,
                        favorite = proxy.name in state.favorites,
                        // Повторное нажатие на закреплённый узел возвращает автовыбор
                        onClick = {
                            onAction(MainAction.SelectProxy(if (proxy.name == group.pinned) "" else proxy.name))
                        },
                        onToggleFavorite = { onAction(MainAction.ToggleFavorite(proxy.name)) },
                        freeze = proxy.name in state.freeze,
                        onFreezeClick = { onAction(MainAction.ShowFreeze) },
                        mobileOnly = proxy.name in mobileOnly,
                        group = proxy.isGroup,
                    )
                }
            }
        }
    }
}

@Composable
internal fun Waiting() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

// hint — почему пусто: нет подписки, в подписке нет групп или пуста группа
@Composable
private fun EmptyServers(@StringRes hint: Int) {
    EmptyState(title = stringResource(R.string.clod_no_servers), hint = stringResource(hint))
}

@Composable
internal fun EmptyState(title: String, hint: String, action: (@Composable () -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(24.dp))
                action()
            }
        }
    }
}

@Composable
private fun GroupSelector(state: ServersState, onAction: (MainAction) -> Unit) {
    val groups = state.groups

    if (groups.size < 2) return

    if (groups.size <= CHIP_GROUPS_LIMIT) {
        ChipGroups(state = state, onAction = onAction)
    } else {
        DropdownGroups(state = state, onAction = onAction)
    }
}

@Composable
private fun ChipGroups(state: ServersState, onAction: (MainAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .chipStripScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.groups.forEachIndexed { index, group ->
            FilterChip(
                selected = index == state.selected,
                onClick = { onAction(MainAction.SelectGroup(index)) },
                label = {
                    Text(
                        text = group.name.bidiIsolated(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = state.icons[group.name]?.let { icon ->
                    { GroupIcon(url = icon, size = 18.dp) }
                },
            )
        }
    }
}

@Composable
private fun DropdownGroups(state: ServersState, onAction: (MainAction) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val selected = state.groups.getOrNull(state.selected)

    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        val selectedIcon = rememberGroupIcon(selected?.name?.let { state.icons[it] })
        val selectedPainter = remember(selectedIcon) { selectedIcon?.let(::BitmapPainter) }

        SelectorRow(
            label = stringResource(
                R.string.clod_group_index,
                state.selected + 1,
                state.groups.size,
            ),
            value = selected?.name.orEmpty().bidiIsolated(),
            onClick = { expanded = true },
            leading = selectedPainter ?: painterResource(R.drawable.ic_nav_servers),
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            state.groups.forEachIndexed { index, group ->
                DropdownMenuItem(
                    modifier = Modifier.semantics { this.selected = index == state.selected },
                    text = {
                        Text(
                            text = group.name.bidiIsolated(),
                            fontWeight = if (index == state.selected) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = state.icons[group.name]?.let { icon ->
                        { GroupIcon(url = icon, size = 20.dp) }
                    },
                    onClick = {
                        expanded = false

                        onAction(MainAction.SelectGroup(index))
                    },
                )
            }
        }
    }
}

private const val CHIP_GROUPS_LIMIT = 3
