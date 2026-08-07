package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.NoServersCard
import com.github.kr328.clash.design.compose.component.ProxyRow
import com.github.kr328.clash.design.compose.component.noServersReason
import com.github.kr328.clash.design.compose.component.SelectorRow

/**
 * Вкладка «Серверы»: выбор группы сверху, узлы выбранной группы списком.
 *
 * Выбор группы выглядит по-разному в зависимости от того, сколько их
 * (см. [GroupSelector]). Ряда прокручиваемых чипов, который был здесь раньше,
 * не осталось: при двух группах он выглядел пустой полосой, а при десяти
 * прокручивался вслепую — не видно ни сколько групп всего, ни есть ли ещё
 * справа.
 */
@Composable
fun ServersTab(
    state: ServersState,
    active: SubscriptionItem?,
    onAction: (MainAction) -> Unit,
) {
    // Серверов может не быть не потому, что список не загрузился, а потому,
    // что их не выдали. Тогда вкладка не притворяется списком, а называет
    // причину — теми же словами, что и главный экран.
    val noServers = noServersReason(active?.profile, active?.panel)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.clod_tab_servers),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (state.testing) {
                // Индикатор ровно того же размера, что и иконка под ним: иначе
                // шапка дёргается по высоте на каждый запуск проверки.
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(
                    onClick = { onAction(MainAction.TestDelays) },
                    // Работает и до подключения: там задержки меряет не ядро,
                    // а разовый разбор файла подписки. Не работает только там,
                    // где ядро уже занято, а групп не отдаёт (см. readOnly).
                    enabled = state.groups.isNotEmpty() && !state.readOnly && noServers == null,
                ) {
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
            EmptyServers()
            return@Column
        }

        GroupSelector(state = state, onAction = onAction)

        if (state.offline) {
            Text(
                text = stringResource(
                    if (state.readOnly) R.string.clod_servers_direct else R.string.clod_servers_offline,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }

        val group = state.groups.getOrNull(state.selected)
        if (group == null || group.proxies.isEmpty()) {
            EmptyServers()
            return@Column
        }

        // Отмеченные звездой — наверх, как на ПК. Сортировка устойчивая,
        // поэтому внутри каждой половины порядок остаётся тот, который прислало
        // ядро (по умолчанию — как в подписке, либо по имени/задержке).
        val proxies = remember(group.proxies, state.favorites) {
            if (state.favorites.isEmpty()) {
                group.proxies
            } else {
                group.proxies.sortedByDescending { it.name in state.favorites }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = proxies, key = { it.name }) { proxy ->
                ProxyRow(
                    title = proxy.title,
                    subtitle = proxy.subtitle,
                    delay = proxy.delay,
                    selected = proxy.name == group.now,
                    favorite = proxy.name in state.favorites,
                    // Нажатие обрабатывается всегда: если выбрать нельзя,
                    // человек получит внятное объяснение, а не тишину.
                    onClick = { onAction(MainAction.SelectProxy(proxy.name)) },
                    onToggleFavorite = { onAction(MainAction.ToggleFavorite(proxy.name)) },
                )
            }
        }
    }
}

@Composable
private fun EmptyServers() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.clod_no_servers),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clod_no_servers_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Выбор группы.
 *
 * Три случая, и в каждом своё:
 *
 *  * **одна группа** — выбирать не из чего, элемента нет вовсе, экран целиком
 *    отдан списку узлов. Это самый частый случай у подписок Remnawave;
 *  * **две-три** — сегментированный переключатель на всю ширину: и переключение
 *    в один тап, и сразу видно, что групп ровно столько;
 *  * **четыре и больше** — одна строка-селектор с выпадающим списком. В строке
 *    написано, какая по счёту группа выбрана из скольких, и сколько в ней узлов.
 *
 * Порог в три группы — от ширины экрана: на 360 dp четвёртый сегмент оставляет
 * на имя меньше семи знаков, и в переключателе оказываются одни многоточия.
 */
@Composable
private fun GroupSelector(state: ServersState, onAction: (MainAction) -> Unit) {
    val groups = state.groups

    if (groups.size < 2) return

    if (groups.size <= SEGMENTED_GROUPS_LIMIT) {
        SegmentedGroups(state = state, onAction = onAction)
    } else {
        DropdownGroups(state = state, onAction = onAction)
    }
}

/** Сегментированный переключатель групп: две-три штуки на всю ширину. */
@Composable
private fun SegmentedGroups(state: ServersState, onAction: (MainAction) -> Unit) {
    val shape = RoundedCornerShape(50)
    val outline = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(IntrinsicSize.Min)
            .border(1.dp, outline, shape)
            .clip(shape),
    ) {
        state.groups.forEachIndexed { index, group ->
            val selected = index == state.selected

            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(outline),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onAction(MainAction.SelectGroup(index)) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Строка-селектор с выпадающим списком: групп четыре и больше. */
@Composable
private fun DropdownGroups(state: ServersState, onAction: (MainAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    val selected = state.groups.getOrNull(state.selected)

    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        SelectorRow(
            label = stringResource(
                R.string.clod_group_index,
                state.selected + 1,
                state.groups.size,
            ),
            value = selected?.name.orEmpty(),
            onClick = { expanded = true },
            leading = painterResource(R.drawable.ic_nav_servers),
            trailing = {
                Text(
                    text = pluralStringResource(
                        R.plurals.clod_nodes_count,
                        selected?.proxies?.size ?: 0,
                        selected?.proxies?.size ?: 0,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            state.groups.forEachIndexed { index, group ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = group.name,
                            fontWeight = if (index == state.selected) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    // Числа узлов у пунктов нет намеренно: состав группы
                    // ядро отдаёт только для открытой, у остальных список
                    // пуст, и в меню стояли бы нули.
                    onClick = {
                        expanded = false

                        onAction(MainAction.SelectGroup(index))
                    },
                )
            }
        }
    }
}

/** До скольких групп включительно показываем сегментированный переключатель. */
private const val SEGMENTED_GROUPS_LIMIT = 3
