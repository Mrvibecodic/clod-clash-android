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

/**
 * Строки для настроек, которые правятся текстом.
 *
 * Раньше это был DSL `preferenceScreen` с отдельным диалогом на каждый пункт
 * и, для списков и карт, ещё и со своим редактором с добавлением по одному
 * элементу. Здесь и то и другое — один диалог с текстовым полем: список
 * это строки, карта — строки вида `ключ = значение`. Так короче и понятнее:
 * список DNS-серверов человек вставляет из буфера целиком, а не по одному.
 */

/** Одно значение: порт, адрес, ключ. */
@Composable
fun TextRow(
    title: String,
    value: String?,
    onValue: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Что показать, когда значение не задано, — «не менять». */
    placeholder: String = stringResource(R.string.dont_modify),
    /** Что показать, когда значение задано пустым, — «выключено», «по умолчанию». */
    empty: String? = null,
    /** Клавиатура только с цифрами: для портов. */
    numeric: Boolean = false,
    /**
     * Годится ли введённое. Пока не годится, кнопка «ОК» не нажимается —
     * молча превращать «абв» в «не менять» нельзя: человек уверен,
     * что значение задал.
     */
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
            // Пустое поле — это «пустое значение» (порт выключен, адрес
            // по умолчанию), а не «не менять»: под «не менять» отдельная
            // кнопка. Эти три состояния в конфигурации разные, и схлопывать
            // их в одно поле нельзя.
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

/** Список значений: серверы имён, порты сниффера, домены. */
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

                // Пустой текст — это пустой список, а не «не менять»:
                // в конфигурации `fallback: []` и отсутствие ключа значат
                // разное. «Не менять» — отдельной кнопкой.
                onValues(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
            },
        )
    }
}

/** Пары «ключ — значение»: hosts, nameserver-policy. */
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

                // Строка без разделителя просто отбрасывается: писать половину
                // пары некуда, а ронять весь список из-за опечатки незачем.
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
                    // Верхняя граница обязательна: у `hosts` и фильтров
                    // бывает по нескольку десятков строк, и поле без неё
                    // выталкивает кнопки за край экрана.
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
                // «Не менять» — это третье состояние, отличное и от пустого
                // значения, и от заданного: настройка просто не участвует.
                TextButton(onClick = onReset) { Text(stringResource(R.string.dont_modify)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
