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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ProxyRow

/**
 * Вкладка «Серверы»: группы чипами сверху, узлы выбранной группы списком.
 *
 * Группы именно чипами, а не вкладками ViewPager, как было у CMFA: у Remnawave
 * групп обычно две-три, и вкладки на весь экран ради этого — перебор, а при
 * десятке групп чипы прокручиваются, тогда как вкладки начинают сжиматься.
 */
@Composable
fun ServersTab(state: ServersState, onAction: (MainAction) -> Unit) {
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
                    // Пока туннель не поднят, мерить нечем: список собран из файла.
                    enabled = state.groups.isNotEmpty() && !state.offline,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_flash_on),
                        contentDescription = stringResource(R.string.clod_test_delays),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (state.groups.isEmpty()) {
            EmptyServers()
            return@Column
        }

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
                    label = { Text(group.name) },
                )
            }
        }

        if (state.offline) {
            Text(
                text = stringResource(R.string.clod_servers_offline),
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
            items(items = group.proxies, key = { it.name }) { proxy ->
                ProxyRow(
                    title = proxy.title,
                    subtitle = proxy.subtitle,
                    delay = proxy.delay,
                    selected = proxy.name == group.now,
                    // Нажатие обрабатывается всегда: если выбрать нельзя,
                    // человек получит внятное объяснение, а не тишину.
                    onClick = { onAction(MainAction.SelectProxy(proxy.name)) },
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
