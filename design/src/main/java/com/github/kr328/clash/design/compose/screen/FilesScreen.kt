package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.model.File
import com.github.kr328.clash.design.util.elapsedIntervalString
import com.github.kr328.clash.design.util.toBytesString
import kotlinx.coroutines.launch

/**
 * @param inBaseDir открыт корень профиля. В корне лежит сам `config.yaml`,
 *   и трогать его как обычный файл нельзя: ни переименовать, ни удалить,
 *   ни создать рядом соседа.
 * @param configurationEditable профиль не привязан к ссылке. У профиля по
 *   ссылке конфигурация перезаписывается при каждом обновлении, поэтому
 *   правка на месте была бы работой, которая молча пропадёт.
 * @param currentTime часы для строки «N минут назад». Приходят снаружи
 *   и обновляются раз в минуту: считать их в теле экрана значило бы
 *   пересчитывать при каждой перерисовке, а меняться от этого они не начнут.
 * @param menuFor файл, для которого открыто меню действий.
 */
data class FilesState(
    val files: List<File> = emptyList(),
    val inBaseDir: Boolean = true,
    val configurationEditable: Boolean = false,
    val currentTime: Long = 0,
    val menuFor: File? = null,
)

sealed interface FilesAction {
    data object Back : FilesAction
    data object New : FilesAction
    data class Open(val file: File) : FilesAction
    data class More(val file: File) : FilesAction
    data object CloseMenu : FilesAction
    data class Import(val file: File) : FilesAction
    data class Export(val file: File) : FilesAction
    data class Rename(val file: File) : FilesAction
    data class Delete(val file: File) : FilesAction
}

/** Экран «Файлы»: содержимое каталога профиля. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    state: FilesState,
    onAction: (FilesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    ActivityScaffold(
        title = stringResource(R.string.files),
        onBack = { onAction(FilesAction.Back) },
        modifier = modifier,
        actions = {
            // В корне профиля добавлять нечего: там живёт сама конфигурация.
            if (!state.inBaseDir) {
                IconButton(onClick = { onAction(FilesAction.New) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_add),
                        contentDescription = stringResource(R.string._new),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            items(state.files, key = { it.id }) { file ->
                FileRow(
                    file = file,
                    elapsed = if (file.isDirectory) {
                        null
                    } else {
                        (state.currentTime - file.lastModified)
                            .coerceAtLeast(0)
                            .elapsedIntervalString(context)
                    },
                    onOpen = { onAction(FilesAction.Open(file)) },
                    onMore = { onAction(FilesAction.More(file)) },
                )
            }
        }
    }

    val target = state.menuFor

    if (target != null) {
        // Лист уезжает вниз анимацией, и только потом снимается состояние:
        // без этого он исчезал бы рывком в момент нажатия.
        val close = { action: FilesAction ->
            scope.launch { sheetState.hide() }.invokeOnCompletion { onAction(action) }
        }

        ModalBottomSheet(
            onDismissRequest = { onAction(FilesAction.CloseMenu) },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                // Заменить содержимое можно у файла, но не у конфигурации
                // профиля по ссылке — её перезапишет ближайшее обновление.
                if (!target.isDirectory && (!state.inBaseDir || state.configurationEditable)) {
                    MenuAction(
                        title = stringResource(R.string.import_),
                        icon = R.drawable.ic_baseline_get_app,
                        onClick = { close(FilesAction.Import(target)) },
                    )
                }

                // Выгружать пустой файл нечего.
                if (!target.isDirectory && target.size > 0) {
                    MenuAction(
                        title = stringResource(R.string.export),
                        icon = R.drawable.ic_baseline_publish,
                        onClick = { close(FilesAction.Export(target)) },
                    )
                }

                if (!state.inBaseDir) {
                    MenuAction(
                        title = stringResource(R.string.rename),
                        icon = R.drawable.ic_baseline_edit,
                        onClick = { close(FilesAction.Rename(target)) },
                    )
                    MenuAction(
                        title = stringResource(R.string.delete),
                        icon = R.drawable.ic_outline_delete,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { close(FilesAction.Delete(target)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuAction(
    title: String,
    icon: Int,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

@Composable
private fun FileRow(
    file: File,
    elapsed: String?,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            painter = painterResource(
                if (file.isDirectory) R.drawable.ic_outline_folder else R.drawable.ic_outline_article,
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!file.isDirectory) {
                Text(
                    text = file.size.toBytesString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (elapsed != null) {
            Text(
                text = elapsed,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMore) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_more_vert),
                contentDescription = stringResource(R.string.more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
