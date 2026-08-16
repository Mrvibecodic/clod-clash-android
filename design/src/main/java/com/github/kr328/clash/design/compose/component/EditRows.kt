package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R

@Composable
fun TextRow(
    title: String,
    value: String?,
    onValue: (String?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.dont_modify),
    empty: String? = null,
    numeric: Boolean = false,
    valid: (String) -> Boolean = { true },
    enabled: Boolean = true,
) {
    var editing by remember { mutableStateOf(false) }

    val shown = when {
        value == null -> placeholder
        value.isBlank() -> empty ?: placeholder
        else -> value
    }

    ValueRow(
        title = title,
        value = shown,
        enabled = enabled,
        onClick = { editing = true },
        modifier = modifier,
    )

    if (editing) {
        var text by remember { mutableStateOf(value.orEmpty()) }

        EditDialog(
            title = title,
            hint = empty ?: "",
            text = text,
            singleLine = true,
            numeric = numeric,
            confirmEnabled = text.isBlank() || valid(text),
            onText = { text = it },
            onDismiss = { editing = false },
            onReset = {
                editing = false

                onValue(null)
            },
            onConfirm = {
                editing = false

                onValue(text)
            },
        )
    }
}

@Composable
fun LinesRow(
    title: String,
    values: List<String>?,
    onValues: (List<String>?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var editing by remember { mutableStateOf(false) }

    val shown = when {
        values == null -> stringResource(R.string.dont_modify)
        values.isEmpty() -> stringResource(R.string.empty)
        else -> values.joinToString(", ")
    }

    ValueRow(
        title = title,
        value = shown,
        enabled = enabled,
        onClick = { editing = true },
        modifier = modifier,
    )

    if (editing) {
        var text by remember { mutableStateOf(values.orEmpty().joinToString("\n")) }

        EditDialog(
            title = title,
            hint = stringResource(R.string.clod_one_per_line),
            text = text,
            singleLine = false,
            onText = { text = it },
            onDismiss = { editing = false },
            onReset = {
                editing = false

                onValues(null)
            },
            onConfirm = {
                editing = false

                onValues(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
            },
        )
    }
}

@Composable
fun PairsRow(
    title: String,
    values: Map<String, String>?,
    onValues: (Map<String, String>?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var editing by remember { mutableStateOf(false) }

    val shown = when {
        values == null -> stringResource(R.string.dont_modify)
        values.isEmpty() -> stringResource(R.string.empty)
        else -> values.entries.joinToString(", ") { "${it.key} = ${it.value}" }
    }

    ValueRow(
        title = title,
        value = shown,
        enabled = enabled,
        onClick = { editing = true },
        modifier = modifier,
    )

    if (editing) {
        var text by remember {
            mutableStateOf(values.orEmpty().entries.joinToString("\n") { "${it.key} = ${it.value}" })
        }

        EditDialog(
            title = title,
            hint = stringResource(R.string.clod_key_value_per_line),
            text = text,
            singleLine = false,
            onText = { text = it },
            onDismiss = { editing = false },
            onReset = {
                editing = false

                onValues(null)
            },
            onConfirm = {
                editing = false

                val pairs = text.lines()
                    .mapNotNull { line ->
                        val index = line.indexOf('=')

                        if (index <= 0) return@mapNotNull null

                        val key = line.take(index).trim()
                        val value = line.substring(index + 1).trim()

                        if (key.isEmpty()) null else key to value
                    }
                    .toMap()

                onValues(pairs)
            },
        )
    }
}

@Composable
private fun ValueRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EditDialog(
    title: String,
    hint: String,
    text: String,
    singleLine: Boolean,
    onText: (String) -> Unit,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onConfirm: () -> Unit,
    numeric: Boolean = false,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                singleLine = singleLine,
                isError = !confirmEnabled,
                keyboardOptions = if (numeric) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                placeholder = { Text(hint) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (singleLine) 0.dp else 140.dp, max = 240.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.dont_modify)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
