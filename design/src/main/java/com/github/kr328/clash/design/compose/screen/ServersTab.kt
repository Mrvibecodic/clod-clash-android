package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * (см. [GroupSelector]): до трёх — чипы в строку, дальше — строка-селектор
 * с выпадающим списком. Ряд чипов при десяти группах прокручивался бы вслепую:
 * не видно ни сколько групп всего, ни есть ли ещё справа.
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

    // Описание узла от провайдера («Франкфурт, 10 Гбит») вместо типа протокола.
    // Тип в подписи узла человеку не говорит ничего: он одинаковый у всех
    // узлов подписки. Описания приходят не от ядра — оно о таком поле не знает,
    // — а из разбора конфигурации, поэтому лежат в данных панели.
    val descriptions = active?.panel?.descriptions.orEmpty()

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
                // Сколько узлов в открытой группе. Число знает только она:
                // состав остальных ядро не отдаёт, пока их не открыли.
                val count = state.groups.getOrNull(state.selected)?.proxies?.size ?: 0

                if (count > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.clod_nodes_count, count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                    subtitle = descriptions[proxy.name]?.takeIf { it.isNotBlank() }
                        ?: proxy.subtitle,
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
 *  * **две-три** — чипы в строку: и переключение в один тап, и сразу видно,
 *    что групп ровно столько;
 *  * **четыре и больше** — одна строка-селектор с выпадающим списком. В строке
 *    написано, какая по счёту группа выбрана из скольких.
 *
 * Порог в три группы — от ширины экрана: четвёртый чип на 360 dp уже уезжает
 * за край, и о его существовании человек не узнает.
 */
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

/**
 * Группы чипами: две-три штуки в строку.
 *
 * Раньше здесь стоял сегментированный переключатель на всю ширину. Он делил
 * ширину поровну между группами независимо от длины имён, и на 360 dp при трёх
 * группах на имя оставалось меньше десяти знаков — «Основн…», «Резерв…».
 * Чип берёт ровно столько, сколько нужно тексту, а выбранный отмечен заливкой
 * `secondaryContainer` — это цветовая роль, а не `primary` под 16 % прозрачности,
 * и контраст у неё посчитан, а не подобран на глаз.
 *
 * Строка прокручивается: имена групп задаёт панель, и упереться в край экрана
 * можно и на двух.
 */
@Composable
private fun ChipGroups(state: ServersState, onAction: (MainAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.groups.forEachIndexed { index, group ->
            FilterChip(
                selected = index == state.selected,
                onClick = { onAction(MainAction.SelectGroup(index)) },
                label = {
                    Text(
                        text = group.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/**
 * Строка-селектор с выпадающим списком: групп четыре и больше.
 *
 * Числа узлов в строке больше нет: то же число теперь стоит в шапке вкладки,
 * а два одинаковых «6 узлов» подряд на одном экране читаются как две разные
 * величины, и человек ищет между ними разницу.
 */
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

/** До скольких групп включительно показываем чипы, а не выпадающий список. */
private const val CHIP_GROUPS_LIMIT = 3
