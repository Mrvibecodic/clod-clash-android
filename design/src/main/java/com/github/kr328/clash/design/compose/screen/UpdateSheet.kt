package com.github.kr328.clash.design.compose.screen

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ReleaseNotes

@Immutable
data class UpdateState(
    val version: String,
    val sizeBytes: Long = 0,
    val notes: String = "",
    val downloading: Boolean = false,
    val progress: Float = 0f,
)

@Composable
fun UpdateDialog(
    state: UpdateState,
    onAction: (MainAction) -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!state.downloading) onAction(MainAction.UpdateLater) },
        title = {
            Text(
                text = stringResource(R.string.clod_update_available, state.version.take(32)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.sizeBytes > 0) {
                    Text(
                        text = Formatter.formatShortFileSize(context, state.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (state.downloading) {
                    Text(
                        text = stringResource(R.string.clod_update_downloading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else if (state.notes.isNotBlank()) {
                    ReleaseNotes(state.notes)
                }
            }
        },
        confirmButton = {
            if (!state.downloading) {
                TextButton(onClick = { onAction(MainAction.UpdateNow) }) {
                    Text(stringResource(R.string.clod_update_install))
                }
            }
        },
        dismissButton = {
            if (!state.downloading) {
                Row {
                    TextButton(onClick = { onAction(MainAction.UpdateSkip) }) {
                        Text(stringResource(R.string.clod_update_skip))
                    }
                    TextButton(onClick = { onAction(MainAction.UpdateLater) }) {
                        Text(stringResource(R.string.clod_update_later))
                    }
                }
            }
        },
    )
}
