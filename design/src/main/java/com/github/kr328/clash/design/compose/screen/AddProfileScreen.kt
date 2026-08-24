package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R

enum class AddProfileStep {
    Input,
    Fetching,
}

@Immutable
data class AddProfileState(
    val url: String = "",
    val step: AddProfileStep = AddProfileStep.Input,
    val progressText: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    val secure: Boolean = false,
)

sealed interface AddProfileAction {
    data class UrlChanged(val url: String) : AddProfileAction
    data class SecureChanged(val secure: Boolean) : AddProfileAction
    data object Submit : AddProfileAction
    data object ScanQr : AddProfileAction
    data object OtherWays : AddProfileAction
}

@Composable
fun AddProfileScreen(
    state: AddProfileState,
    onAction: (AddProfileAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
    ) {
        Text(
            text = stringResource(R.string.clod_sub_add),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))

        when (state.step) {
            AddProfileStep.Input -> InputStep(state, onAction)
            AddProfileStep.Fetching -> FetchingStep(state)
        }
    }
}

@Composable
private fun InputStep(state: AddProfileState, onAction: (AddProfileAction) -> Unit) {
    OutlinedTextField(
        value = state.url,
        onValueChange = { onAction(AddProfileAction.UrlChanged(it)) },
        label = { Text(stringResource(R.string.clod_sub_url_label)) },
        singleLine = true,
        isError = state.error != null,
        supportingText = state.error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = { onAction(AddProfileAction.ScanQr) }) {
                Icon(
                    painter = painterResource(R.drawable.baseline_qr_code_scanner),
                    contentDescription = stringResource(R.string.import_from_qr),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.clod_sub_url_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAction(AddProfileAction.SecureChanged(!state.secure)) },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.clod_secure_channel),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.clod_secure_channel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = state.secure,
            onCheckedChange = { onAction(AddProfileAction.SecureChanged(it)) },
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { onAction(AddProfileAction.Submit) },
        enabled = state.url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.clod_sub_add))
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = { onAction(AddProfileAction.OtherWays) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.clod_sub_other_ways))
    }
}

@Composable
private fun FetchingStep(state: AddProfileState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = state.progressText.ifBlank { stringResource(R.string.clod_sub_fetching) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (state.progress > 0f) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

