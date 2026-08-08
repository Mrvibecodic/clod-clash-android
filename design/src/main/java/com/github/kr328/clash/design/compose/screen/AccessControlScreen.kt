package com.github.kr328.clash.design.compose.screen

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.rememberDrawablePainter
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.model.AppInfoSort

/**
 * @param loaded список приложений уже прочитан у системы. Отдельно от пустого
 *   списка: до чтения он тоже пуст, и без флага экран мигал бы пустотой
 *   вместо ожидания — а читается он у системы секунду и дольше.
 * @param selected снимок выбранного, а не сам изменяемый набор. Настоящий
 *   набор живёт в активити (она сохраняет его при уходе с экрана), но Compose
 *   перерисовывается по неравенству значений, и на изменяемом множестве
 *   галочки не двигались бы вовсе.
 * @param query строка поиска. Пустая — показываем всё; отдельного экрана
 *   поиска, как было на XML, больше нет: список фильтруется на месте.
 */
data class AccessControlState(
    val apps: List<AppInfo> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val searching: Boolean = false,
    val query: String = "",
    val sort: AppInfoSort = AppInfoSort.Label,
    val reverse: Boolean = false,
    val systemApps: Boolean = false,
)

sealed interface AccessControlAction {
    data object Back : AccessControlAction
    data object SelectAll : AccessControlAction
    data object SelectNone : AccessControlAction
    data object SelectInvert : AccessControlAction
    data object Import : AccessControlAction
    data object Export : AccessControlAction
    data class Toggle(val packageName: String) : AccessControlAction
    data class Search(val enabled: Boolean) : AccessControlAction
    data class Query(val value: String) : AccessControlAction
    data class Sort(val value: AppInfoSort) : AccessControlAction
    data class Reverse(val value: Boolean) : AccessControlAction
    data class SystemApps(val value: Boolean) : AccessControlAction
}

/**
 * Экран «Приложения»: какие приложения пускать в туннель.
 *
 * Поиск встроен в шапку, а не вынесен в полноэкранный диалог со своим вторым
 * списком и своим вторым адаптером, как было на XML. Тот диалог показывал
 * пустоту, пока не начнёшь печатать, и держал отдельную копию выбранного,
 * которую после закрытия приходилось сводить обратно вызовом `rebindAll`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessControlScreen(
    state: AccessControlState,
    onAction: (AccessControlAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Системный «назад» из поиска возвращает к списку, а не с экрана. Раньше
    // поиск был отдельным полноэкранным диалогом, и назад закрывал его сам;
    // без этого набранное слово и экран уходили бы одним движением, а уход
    // с экрана ещё и сохраняет список и перезапускает ядро.
    BackHandler(enabled = state.searching) {
        onAction(AccessControlAction.Search(false))
    }

    val visible = remember(state.apps, state.query) {
        val keyword = state.query.trim()

        if (keyword.isEmpty()) {
            state.apps
        } else {
            state.apps.filter {
                it.label.contains(keyword, ignoreCase = true) ||
                    it.packageName.contains(keyword, ignoreCase = true)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (state.searching) {
                        SearchField(
                            query = state.query,
                            onQuery = { onAction(AccessControlAction.Query(it)) },
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.access_control_packages),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Из поиска стрелка возвращает к списку, а не с экрана:
                            // иначе набранное слово и экран уходили бы одним нажатием.
                            if (state.searching) {
                                onAction(AccessControlAction.Search(false))
                            } else {
                                onAction(AccessControlAction.Back)
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_arrow_back),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                actions = {
                    // В поиске обе кнопки убраны: место занимает поле ввода,
                    // а сортировать и выделять всё поверх фильтра — не то,
                    // за чем сюда приходят.
                    if (!state.searching) {
                        IconButton(onClick = { onAction(AccessControlAction.Search(true)) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_search),
                                contentDescription = stringResource(R.string.search),
                            )
                        }

                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_baseline_more_vert),
                                    contentDescription = stringResource(R.string.more),
                                )
                            }

                            AccessControlMenu(
                                expanded = menuOpen,
                                state = state,
                                onDismiss = { menuOpen = false },
                                onAction = onAction,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (!state.loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            return@Scaffold
        }

        LazyColumn(contentPadding = padding) {
            items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    selected = app.packageName in state.selected,
                    onClick = { onAction(AccessControlAction.Toggle(app.packageName)) },
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Поле появилось — значит человек нажал «Поиск» и собирается печатать.
    LaunchedEffect(Unit) {
        focus.requestFocus()
    }

    TextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
}

@Composable
private fun AccessControlMenu(
    expanded: Boolean,
    state: AccessControlState,
    onDismiss: () -> Unit,
    onAction: (AccessControlAction) -> Unit,
) {
    // Плоское меню вместо трёх вложенных подменю с XML: у Compose вложенных
    // выпадающих меню нет, а прятать «Сортировку» за вторым нажатием при
    // семи пунктах всего — дороже, чем показать их сразу.
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val pick = { action: AccessControlAction ->
            onDismiss()
            onAction(action)
        }

        DropdownMenuItem(
            text = { Text(stringResource(R.string.select_all)) },
            onClick = { pick(AccessControlAction.SelectAll) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.select_none)) },
            onClick = { pick(AccessControlAction.SelectNone) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.select_invert)) },
            onClick = { pick(AccessControlAction.SelectInvert) },
        )

        MenuSection(stringResource(R.string.filter))
        CheckableItem(
            title = stringResource(R.string.system_apps),
            checked = state.systemApps,
        ) {
            pick(AccessControlAction.SystemApps(!state.systemApps))
        }

        MenuSection(stringResource(R.string.sort))
        for (sort in AppInfoSort.entries) {
            CheckableItem(title = sort.title(), checked = state.sort == sort) {
                pick(AccessControlAction.Sort(sort))
            }
        }
        CheckableItem(
            title = stringResource(R.string.reverse),
            checked = state.reverse,
        ) {
            pick(AccessControlAction.Reverse(!state.reverse))
        }

        MenuSection(stringResource(R.string.external))
        DropdownMenuItem(
            text = { Text(stringResource(R.string.import_from_clipboard)) },
            onClick = { pick(AccessControlAction.Import) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.export_to_clipboard)) },
            onClick = { pick(AccessControlAction.Export) },
        )
    }
}

@Composable
private fun AppInfoSort.title(): String = stringResource(
    when (this) {
        AppInfoSort.Label -> R.string.name
        AppInfoSort.PackageName -> R.string.package_name
        AppInfoSort.InstallTime -> R.string.install_time
        AppInfoSort.UpdateTime -> R.string.update_time
    },
)

@Composable
private fun MenuSection(title: String) {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun CheckableItem(title: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        onClick = onClick,
        trailingIcon = {
            if (checked) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
private fun AppRow(
    app: AppInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Именно `toggleable`, а не `clickable`: голосовой доступ должен
            // объявить строку галочкой и назвать её состояние, а не сказать
            // «кнопка» на список из трёхсот приложений.
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        AppIcon(app.icon)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(
            checked = selected,
            // Нажатие ловит вся строка: попасть в квадратик 20×20 пальцем
            // на списке из трёхсот приложений — не задача пользователя.
            onCheckedChange = null,
        )
    }
}

/** Иконка приложения — как есть, без растеризации (см. [DrawablePainter]). */
@Composable
private fun AppIcon(drawable: Drawable) {
    Image(
        painter = rememberDrawablePainter(drawable),
        contentDescription = null,
        modifier = Modifier.size(40.dp),
    )
}
