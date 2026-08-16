package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.SubScreenScaffold
import com.github.kr328.clash.design.compose.component.SwitchRow

@Immutable
data class AboutState(
    val versionName: String = "",
    val coreVersion: String = "",
    val autoCheckUpdate: Boolean = true,
    val prerelease: Boolean = false,
    val checking: Boolean = false,
)

@Composable
fun AboutScreen(
    state: AboutState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubScreenScaffold(
        title = stringResource(R.string.about),
        onBack = { onAction(MainAction.CloseSubScreen) },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_clash),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.application_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.versionName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (state.coreVersion.isNotBlank()) {
                Text(
                    text = stringResource(R.string.clod_about_core, state.coreVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { onAction(MainAction.CheckUpdate) },
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(stringResource(R.string.clod_update_check))
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        SwitchRow(
            title = stringResource(R.string.clod_update_auto),
            subtitle = stringResource(R.string.clod_update_auto_subtitle),
            checked = state.autoCheckUpdate,
            onCheckedChange = { onAction(MainAction.SetAutoCheckUpdate(it)) },
        )
        SwitchRow(
            title = stringResource(R.string.clod_update_prerelease),
            subtitle = stringResource(R.string.clod_update_prerelease_subtitle),
            checked = state.prerelease,
            onCheckedChange = { onAction(MainAction.SetPrerelease(it)) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.clod_about_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
