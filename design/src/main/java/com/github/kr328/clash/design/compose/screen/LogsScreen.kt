package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.util.format

/**
 * @param loaded каталог уже прочитан. Отдельно от пустого списка: до чтения
 *   с диска список тоже пуст, и без этого флага экран на первом кадре успевал
 *   мигнуть надписью «логов нет».
 * @param confirmingDelete показан вопрос об удалении всей истории. Часть
 *   состояния экрана, а не отдельный системный диалог: экран сам решает,
 *   что показывает, и снаружи это выглядит как одно состояние, а не как
 *   окно, живущее своей жизнью.
 */
@Immutable
data class LogsState(
    val files: List<LogFile> = emptyList(),
    val loaded: Boolean = false,
    val confirmingDelete: Boolean = false,
)

sealed interface LogsAction {
    data object Back : LogsAction
    data object StartLogcat : LogsAction
    data object RequestDeleteAll : LogsAction
    data object ConfirmDeleteAll : LogsAction
    data object CancelDeleteAll : LogsAction
    data class OpenFile(val file: LogFile) : LogsAction
}

/**
 * Экран логов: запуск живой записи сверху, сохранённые файлы ниже.
 *
 * Список на `LazyColumn` вместо `RecyclerView` с адаптером — отдельного
 * класса-адаптера и разметки строки больше не нужно.
 */
@Composable
fun LogsScreen(
    state: LogsState,
    onAction: (LogsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    ActivityScaffold(
        title = stringResource(R.string.logs),
        onBack = { onAction(LogsAction.Back) },
        modifier = modifier,
        actions = {
            // Кнопка нужна, только когда есть что удалять.
            if (state.files.isNotEmpty()) {
                IconButton(onClick = { onAction(LogsAction.RequestDeleteAll) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_clear_all),
                        contentDescription = stringResource(R.string.delete_all_logs),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Column {
                    ActionRow(
                        title = stringResource(R.string.clash_logcat),
                        subtitle = stringResource(R.string.tap_to_start),
                        icon = painterResource(R.drawable.ic_baseline_adb),
                        onClick = { onAction(LogsAction.StartLogcat) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    SectionHeader(stringResource(R.string.history))
                }
            }

            if (state.loaded && state.files.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.clod_no_logs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 32.dp),
                    )
                }
            }

            items(state.files, key = { it.fileName }) { file ->
                ActionRow(
                    title = file.date.format(context),
                    subtitle = file.fileName,
                    icon = painterResource(R.drawable.ic_outline_article),
                    onClick = { onAction(LogsAction.OpenFile(file)) },
                )
            }
        }
    }

    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { onAction(LogsAction.CancelDeleteAll) },
            title = { Text(stringResource(R.string.delete_all_logs)) },
            text = { Text(stringResource(R.string.delete_all_logs_warn)) },
            confirmButton = {
                TextButton(onClick = { onAction(LogsAction.ConfirmDeleteAll) }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(LogsAction.CancelDeleteAll) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
