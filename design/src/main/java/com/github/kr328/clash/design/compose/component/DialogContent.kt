package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Содержимое диалогов, оставшихся от XML.
 *
 * Рамка диалога (заголовок и кнопки) по-прежнему материаловская —
 * `MaterialAlertDialogBuilder`, — а внутрь ставится `ComposeView`. Так
 * сохраняются приостанавливаемые вызовы, которыми пользуются экраны
 * (`requestModelTextInput` возвращает строку и ждёт человека), и при этом
 * из проекта уходят последние разметки, а вместе с ними `dataBinding`.
 */

/**
 * Поле ввода с проверкой значения.
 *
 * @param onChanged зовётся и на первой отрисовке: диалогу нужно состояние
 *   кнопки «ОК» ДО того, как человек тронет поле. Пустое имя файла не должно
 *   быть подтверждаемым с самого начала.
 */
@Composable
fun TextInputContent(
    initial: String,
    isValid: (String) -> Boolean,
    onChanged: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
) {
    // Всё выделено: поле открывается с готовым значением, и чаще его меняют
    // целиком, а не дописывают. Так было и на XML (`setSelection(0, length)`).
    var value by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val invalid = !isValid(value.text)

    LaunchedEffect(Unit) {
        onChanged(value.text, !invalid)

        focusRequester.requestFocus()
        keyboard?.show()
    }

    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it

            onChanged(it.text, isValid(it.text))
        },
        singleLine = true,
        isError = invalid && error != null,
        label = if (hint != null) {
            { Text(text = hint) }
        } else {
            null
        },
        supportingText = if (invalid && error != null) {
            { Text(text = error) }
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
    )
}

/**
 * Полоса выполнения с подписью.
 *
 * Неопределённое состояние — не украшение: пока идёт подготовка, шагов ещё
 * никто не считал, и ползунок на нуле выглядел бы как зависший.
 */
@Composable
fun ProgressContent(
    indeterminate: Boolean,
    progress: Int,
    max: Int,
    text: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        if (indeterminate || max <= 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress.toFloat() / max.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!text.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Ключи age: секретный и открытый, с генерацией, выводом открытого из
 * секретного и копированием каждого.
 *
 * Проверка значений идёт через ядро ([isSecretValid], [isPublicValid]):
 * форма ключа — его дело, а не наше.
 */
@Composable
fun AgeKeyContent(
    secretKey: String,
    publicKey: String,
    onSecretKeyChange: (String) -> Unit,
    onPublicKeyChange: (String) -> Unit,
    isSecretValid: (String) -> Boolean,
    isPublicValid: (String) -> Boolean,
    onGenerate: () -> Unit,
    onDerivePublic: () -> Unit,
    onCopySecret: () -> Unit,
    onCopyPublic: () -> Unit,
    secretLabel: String,
    publicLabel: String,
    secretError: String,
    publicError: String,
    generateLabel: String,
    derivePublicLabel: String,
    copyLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        // Пустое поле ошибкой не считается: человек ещё не начал, ругаться
        // на него не за что.
        val secretInvalid = secretKey.isNotBlank() && !isSecretValid(secretKey)
        val publicInvalid = publicKey.isNotBlank() && !isPublicValid(publicKey)

        OutlinedTextField(
            value = secretKey,
            onValueChange = onSecretKeyChange,
            singleLine = true,
            isError = secretInvalid,
            label = { Text(text = secretLabel) },
            supportingText = if (secretInvalid) {
                { Text(text = secretError) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onGenerate) { Text(text = generateLabel) }
            TextButton(onClick = onCopySecret) { Text(text = copyLabel) }
        }

        OutlinedTextField(
            value = publicKey,
            onValueChange = onPublicKeyChange,
            singleLine = true,
            isError = publicInvalid,
            label = { Text(text = publicLabel) },
            supportingText = if (publicInvalid) {
                { Text(text = publicError) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDerivePublic) { Text(text = derivePublicLabel) }
            TextButton(onClick = onCopyPublic) { Text(text = copyLabel) }
        }
    }
}
