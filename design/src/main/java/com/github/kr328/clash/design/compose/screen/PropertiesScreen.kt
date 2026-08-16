package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ActivityScaffold

@Immutable
data class FetchProgress(
    val text: String,
    val progress: Float = -1f,
)

@Immutable
data class PropertiesState(
    val name: String = "",
    val url: String = "",
    val intervalMinutes: String = "",
    val urlEditable: Boolean = true,
    val intervalEditable: Boolean = true,
    val processing: FetchProgress? = null,
    val confirmingExit: Boolean = false,
)

fun isHttpUrl(value: String): Boolean =
    value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)

fun isValidInterval(minutes: String): Boolean =
    minutes.isBlank() || (minutes.toLongOrNull() ?: 0) >= MIN_INTERVAL_MINUTES

const val MIN_INTERVAL_MINUTES = 15L

sealed interface PropertiesAction {
    data object Back : PropertiesAction
    data object Commit : PropertiesAction
    data object BrowseFiles : PropertiesAction
    data class NameChanged(val value: String) : PropertiesAction
    data class UrlChanged(val value: String) : PropertiesAction
    data class IntervalChanged(val value: String) : PropertiesAction
    data object ConfirmExit : PropertiesAction
    data object CancelExit : PropertiesAction
}

@Composable
fun PropertiesScreen(
    state: PropertiesState,
    onAction: (PropertiesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val processing = state.processing != null

    ActivityScaffold(
        title = stringResource(R.string.properties),
        onBack = { onAction(PropertiesAction.Back) },
        modifier = modifier,
        actions = {
            if (processing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .size(22.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                FilledTonalIconButton(
                    onClick = { onAction(PropertiesAction.Commit) },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_save),
                        contentDescription = stringResource(R.string.save),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                Tip()

                Spacer(Modifier.height(16.dp))

                val nameBlank = state.name.isBlank()

                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onAction(PropertiesAction.NameChanged(it)) },
                    label = { Text(stringResource(R.string.name)) },
                    placeholder = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true,
                    enabled = !processing,
                    isError = nameBlank,
                    supportingText = if (nameBlank) {
                        { Text(stringResource(R.string.should_not_be_blank)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                val urlBroken = state.urlEditable && state.url.isNotBlank() &&
                    !isHttpUrl(state.url)

                OutlinedTextField(
                    value = state.url,
                    onValueChange = { onAction(PropertiesAction.UrlChanged(it)) },
                    label = { Text(stringResource(R.string.url)) },
                    placeholder = { Text(stringResource(R.string.accept_http_content)) },
                    singleLine = true,
                    enabled = state.urlEditable && !processing,
                    isError = urlBroken,
                    supportingText = if (urlBroken) {
                        { Text(stringResource(R.string.accept_http_content)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                val intervalBroken = !isValidInterval(state.intervalMinutes)

                OutlinedTextField(
                    value = state.intervalMinutes,
                    onValueChange = { onAction(PropertiesAction.IntervalChanged(it)) },
                    label = { Text(stringResource(R.string.auto_update)) },
                    placeholder = { Text(stringResource(R.string.disabled)) },
                    singleLine = true,
                    enabled = state.intervalEditable && !processing,
                    isError = intervalBroken,
                    supportingText = {
                        Text(
                            if (intervalBroken) {
                                stringResource(R.string.at_least_15_minutes)
                            } else {
                                stringResource(R.string.auto_update_minutes)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
            }

            ActionRow(
                title = stringResource(R.string.browse_files),
                subtitle = stringResource(R.string.browse_configuration_providers),
                icon = painterResource(R.drawable.ic_outline_folder),
                onClick = { onAction(PropertiesAction.BrowseFiles) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    state.processing?.let { ProgressDialog(it) }

    if (state.confirmingExit) {
        AlertDialog(
            onDismissRequest = { onAction(PropertiesAction.CancelExit) },
            title = { Text(stringResource(R.string.exit_without_save)) },
            text = { Text(stringResource(R.string.exit_without_save_warning)) },
            confirmButton = {
                TextButton(onClick = { onAction(PropertiesAction.ConfirmExit) }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(PropertiesAction.CancelExit) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Tip() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_outline_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.clod_properties_tip),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressDialog(progress: FetchProgress) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.save)) },
        text = {
            Column {
                Text(
                    text = progress.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (progress.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
}
