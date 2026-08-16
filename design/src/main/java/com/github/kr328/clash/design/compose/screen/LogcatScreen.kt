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
import androidx.compose.runtime.Immutable
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

@Immutable
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

@Composable
fun LogcatScreen(
    state: LogcatState,
    onAction: (LogcatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    val atBottom = {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1

        last < 0 || last >= info.totalItemsCount - 2
    }

    LaunchedEffect(state.messages) {
        if (state.streaming && state.messages.isNotEmpty() && atBottom()) {
            listState.scrollToItem(state.messages.size - 1)
        }
    }

    ActivityScaffold(
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
