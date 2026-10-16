package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.statusContainer
import com.github.kr328.clash.design.compose.theme.statusText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsBypassSheet(onAction: (MainAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.AppsBypassLater) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.clod_apps_bypass_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clod_apps_bypass_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clod_apps_bypass_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onAction(MainAction.AppsBypassChoose) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.clod_apps_bypass_choose))
            }
            TextButton(
                onClick = { onAction(MainAction.AppsBypassLater) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.clod_apps_bypass_later))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun AppsBypassCard(onAction: (MainAction) -> Unit, modifier: Modifier = Modifier) {
    val warn = ClodTheme.extraColors.statusConnecting

    Surface(
        onClick = { onAction(MainAction.OpenAccessControl) },
        shape = RoundedCornerShape(16.dp),
        color = warn.statusContainer(MaterialTheme.colorScheme.background),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.clod_apps_bypass_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = warn.statusText(),
                )
                Text(
                    text = stringResource(R.string.clod_apps_bypass_card),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.clod_apps_bypass_card_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
