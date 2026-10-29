package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R

@Composable
fun UnreadableSettingsCard(
    detail: String,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AccentCard(
        accent = MaterialTheme.colorScheme.error,
        icon = painterResource(R.drawable.ic_baseline_info),
        title = stringResource(R.string.clod_override_unreadable_title),
        message = stringResource(R.string.clod_override_unreadable_message),
        modifier = modifier,
    ) {
        Spacer(Modifier.height(6.dp))

        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Button(onClick = onReset) {
            Text(text = stringResource(R.string.clod_reset_action))
        }
    }
}
