package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.util.format

/**
 * @param streaming живая запись, а не открытый файл. У живой сверху кнопка
 *   «остановить», у файла — «удалить» и «выгрузить»; список сам едет за новыми
 *   строками, пока человек не отлистал назад.
 * @param firstLine сквозной номер первой строки в [messages]. Буфер живой
 *   записи хранит последние 128 сообщений и выбрасывает старые с начала,
 *   поэтому номер строки в списке — не её опознание: без сквозного счёта
 *   текст съезжал бы под пальцем у того, кто отлистал назад читать.
 */
data class LogcatState(
    val messages: List<LogMessage> = emptyList(),
    val streaming: Boolean = false,
    val firstLine: Long = 0,
)

sealed interface LogcatAction {
    data object Back : LogcatAction
    data object Close : LogcatAction
    data object Delete : LogcatAction
    data object Export : LogcatAction
    data class Copy(val message: LogMessage) : LogcatAction
}

/** Экран логов ядра. */
@Composable
fun LogcatScreen(
    state: LogcatState,
    onAction: (LogcatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Свежие строки внизу, и список едет за ними — но только если человек
    // и так смотрел на конец: отлистал назад читать — не увозим.
    //
    // ВНИМАНИЕ, отличие от старого экрана: живая запись показывала свежее
    // СВЕРХУ (перевёрнутая раскладка RecyclerView), а открытый файл —
    // сверху вниз по времени. Теперь оба одинаковы и по времени сверху вниз.
    val atBottom = {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1

        last < 0 || last >= info.totalItemsCount - 2
    }

    // Ключ по СОДЕРЖИМОМУ, а не по размеру: буфер живой записи упирается
    // в свои 128 строк и дальше держит размер неизменным — на размере
    // прокрутка отключилась бы навсегда ровно тогда, когда она нужна.
    LaunchedEffect(state.messages) {
        if (state.streaming && state.messages.isNotEmpty() && atBottom()) {
            listState.scrollToItem(state.messages.size - 1)
        }
    }

    ActivityScaffold(
        // Именно `logcat`: этой же строкой экран подписан в манифесте,
        // и в списке недавних задач заголовок должен совпадать.
        title = stringResource(R.string.logcat),
        onBack = { onAction(LogcatAction.Back) },
        modifier = modifier,
        actions = {
            if (state.streaming) {
                IconButton(onClick = { onAction(LogcatAction.Close) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_stop),
                        contentDescription = stringResource(R.string.close),
                    )
                }
            } else {
                IconButton(onClick = { onAction(LogcatAction.Delete) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_delete),
                        contentDescription = stringResource(R.string.delete),
                    )
                }
                IconButton(onClick = { onAction(LogcatAction.Export) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_publish),
                        contentDescription = stringResource(R.string.export),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(state = listState, contentPadding = padding) {
            itemsIndexed(
                state.messages,
                key = { index, _ -> state.firstLine + index },
            ) { _, message ->
                LogRow(message = message, onCopy = { onAction(LogcatAction.Copy(message)) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(message: LogMessage, onCopy: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Копирование по долгому нажатию — как было; короткое нажатие
            // на строку лога не делает ничего и раньше.
            .combinedClickable(onClick = {}, onLongClick = onCopy)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = message.level.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = message.time.format(context, includeDate = false, includeTime = true),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = message.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
