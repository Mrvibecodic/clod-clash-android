package com.github.kr328.clash.design.compose.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import kotlinx.coroutines.delay

private const val NOTICE_SHORT_MILLIS = 2500L
private const val NOTICE_LONG_MILLIS = 5000L

@Immutable
data class Notice(
    val id: Long,
    val text: String,
    val longDuration: Boolean,
    val detail: String?,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
)

@Stable
class NoticeState {
    var current by mutableStateOf<Notice?>(null)
        private set

    private var counter: Long = 0

    fun show(
        text: String,
        longDuration: Boolean,
        detail: String? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        counter += 1

        current = Notice(counter, text, longDuration, detail, actionLabel, onAction)
    }

    fun dismiss(id: Long) {
        if (current?.id == id) {
            current = null
        }
    }
}

@Composable
fun NoticeHost(state: NoticeState, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp) {
    val notice = state.current

    var shown by remember { mutableStateOf<Notice?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }

    if (notice != null) {
        shown = notice
    }

    LaunchedEffect(notice?.id) {
        val active = notice ?: return@LaunchedEffect

        delay(if (active.longDuration) NOTICE_LONG_MILLIS else NOTICE_SHORT_MILLIS)

        state.dismiss(active.id)
    }

    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val message = shown ?: return@AnimatedVisibility

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + bottomInset),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )

                val actionLabel = message.actionLabel
                    ?: message.detail?.let { stringResource(R.string.detail) }

                if (actionLabel != null) {
                    Spacer(Modifier.width(8.dp))

                    TextButton(
                        onClick = {
                            state.dismiss(message.id)

                            val handler = message.onAction

                            if (handler != null) {
                                handler()
                            } else {
                                detail = message.detail
                            }
                        },
                    ) {
                        Text(
                            text = actionLabel,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    detail?.let { text ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { detail = null }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}
