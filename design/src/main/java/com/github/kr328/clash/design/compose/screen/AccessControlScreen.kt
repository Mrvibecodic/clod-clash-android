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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
import com.github.kr328.clash.design.compose.component.SelectRow
import com.github.kr328.clash.design.compose.component.rememberDrawablePainter
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.model.AppInfoSort
import java.util.Locale

@Immutable
data class AccessControlState(
    val apps: List<AppInfo> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val searching: Boolean = false,
    val query: String = "",
    val sort: AppInfoSort = AppInfoSort.Label,
    val reverse: Boolean = false,
    val systemApps: Boolean = false,
    val mode: Int = 0,
    val includeFromProfile: Set<String> = emptySet(),
    val excludeFromProfile: Set<String> = emptySet(),
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
    data class Mode(val index: Int) : AccessControlAction
    data class Sort(val value: AppInfoSort) : AccessControlAction
    data class Reverse(val value: Boolean) : AccessControlAction
    data class SystemApps(val value: Boolean) : AccessControlAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessControlScreen(
    state: AccessControlState,
    onAction: (AccessControlAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

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
                            text = stringResource(R.string.clod_apps),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
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
            if (!state.searching) {
                item(key = "mode") {
                    Column {
                        SelectRow(
                            title = stringResource(R.string.access_control_mode),
                            options = listOf(
                                stringResource(R.string.allow_all_apps),
                                stringResource(R.string.allow_selected_apps),
                                stringResource(R.string.deny_selected_apps),
                            ),
                            selectedIndex = state.mode,
                            icon = painterResource(R.drawable.ic_baseline_vpn_lock),
                            onSelect = { onAction(AccessControlAction.Mode(it)) },
                        )

                        if (state.mode == MODE_ACCEPT_ALL) {
                            Text(
                                text = stringResource(R.string.clod_access_control_all_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 18.dp,
                                    end = 18.dp,
                                    bottom = 8.dp,
                                ),
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    selected = app.packageName in state.selected,
                    profileNote = when (app.packageName) {
                        in state.excludeFromProfile -> stringResource(R.string.clod_access_sub_exclude)
                        in state.includeFromProfile -> stringResource(R.string.clod_access_sub_include)
                        else -> null
                    },
                    onClick = { onAction(AccessControlAction.Toggle(app.packageName)) },
                )
            }
        }
    }
}

private const val MODE_ACCEPT_ALL = 0

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        withFrameNanos { }

        runCatching { focus.requestFocus() }
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
        text = title.uppercase(Locale.getDefault()),
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
    profileNote: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            if (profileNote != null) {
                Text(
                    text = profileNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Checkbox(
            checked = selected,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun AppIcon(drawable: Drawable) {
    Image(
        painter = rememberDrawablePainter(drawable),
        contentDescription = null,
        modifier = Modifier.size(40.dp),
    )
}
