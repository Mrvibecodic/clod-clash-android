package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint

@Immutable
data class DiagnosticsSettingsState(
    val diagnosticsConfigured: Boolean = false,
    val diagnosticsEndpoint: String = "",
    val vpnServiceRunning: Boolean = false,
)

sealed interface DiagnosticsSettingsAction {
    data object Back : DiagnosticsSettingsAction
    data class SaveDiagnosticsCredential(
        val endpoint: String,
        val username: String,
        val password: String,
        val controllerSecret: String,
        val remotePort: Int,
    ) : DiagnosticsSettingsAction
    data object ClearDiagnosticsCredential : DiagnosticsSettingsAction
}

@Composable
fun DiagnosticsSettingsScreen(
    state: DiagnosticsSettingsState,
    onAction: (DiagnosticsSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var endpoint by remember(state.diagnosticsEndpoint) { mutableStateOf(state.diagnosticsEndpoint) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var controllerSecret by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("") }

    fun clearCredentialInputs() {
        username = ""
        password = ""
        controllerSecret = ""
        remotePort = ""
    }

    ActivityScaffold(
        title = stringResource(R.string.diagnostics_credential_title),
        onBack = { onAction(DiagnosticsSettingsAction.Back) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(
                stringResource(
                    if (state.diagnosticsConfigured) {
                        R.string.diagnostics_credential_replace
                    } else {
                        R.string.diagnostics_credential_setup
                    },
                ),
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text(stringResource(R.string.diagnostics_endpoint)) },
                enabled = !state.vpnServiceRunning,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.diagnostics_username)) },
                enabled = !state.vpnServiceRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.diagnostics_password)) },
                enabled = !state.vpnServiceRunning,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = controllerSecret,
                onValueChange = { controllerSecret = it },
                label = { Text(stringResource(R.string.diagnostics_controller_secret)) },
                enabled = !state.vpnServiceRunning,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = remotePort,
                onValueChange = { remotePort = it },
                label = { Text(stringResource(R.string.diagnostics_remote_port)) },
                enabled = !state.vpnServiceRunning,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            if (state.diagnosticsConfigured) {
                OutlinedButton(
                    enabled = !state.vpnServiceRunning,
                    onClick = {
                        onAction(DiagnosticsSettingsAction.ClearDiagnosticsCredential)
                        clearCredentialInputs()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
            Button(
                enabled = !state.vpnServiceRunning &&
                    normalizeDiagnosticsEndpoint(endpoint) != null && run {
                        val allCredentialFieldsBlank = username.isBlank() &&
                            password.isBlank() &&
                            controllerSecret.isBlank() &&
                            remotePort.isBlank()
                        (state.diagnosticsConfigured && allCredentialFieldsBlank) ||
                            DiagnosticsCredential.create(
                                username,
                                password,
                                controllerSecret,
                                remotePort.toIntOrNull() ?: -1,
                            ) != null
                    },
                onClick = {
                    onAction(
                        DiagnosticsSettingsAction.SaveDiagnosticsCredential(
                            endpoint = endpoint,
                            username = username,
                            password = password,
                            controllerSecret = controllerSecret,
                            remotePort = remotePort.toIntOrNull() ?: -1,
                        ),
                    )
                    clearCredentialInputs()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.save))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
