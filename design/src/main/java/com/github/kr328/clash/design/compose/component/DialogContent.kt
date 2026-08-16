package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun TextInputContent(
    initial: String,
    isValid: (String) -> Boolean,
    onChanged: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
) {
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
