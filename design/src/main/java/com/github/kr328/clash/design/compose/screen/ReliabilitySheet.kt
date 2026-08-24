package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R

@Immutable
data class ReliabilityState(
    val prompt: Boolean = false,
    val batteryIgnored: Boolean = false,
    val alwaysOn: Boolean? = null,
)

@Composable
private fun ReliabilityStep(
    title: String,
    subtitle: String,
    iconRes: Int,
    done: Boolean,
    onClick: () -> Unit,
    subtitleDone: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (subtitleDone) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(
                if (done) R.drawable.ic_baseline_check else R.drawable.ic_chevron_right,
            ),
            contentDescription = null,
            tint = if (done) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun alwaysOnSubtitle(alwaysOn: Boolean?): String = when (alwaysOn) {
    true -> stringResource(R.string.clod_reliability_always_on_enabled)
    false -> stringResource(R.string.clod_reliability_always_on_disabled)
    null -> stringResource(R.string.clod_reliability_always_on_unknown)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliabilitySheet(state: ReliabilityState, onAction: (MainAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.ReliabilityDismiss) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.clod_reliability_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clod_reliability_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            ReliabilityStep(
                title = stringResource(R.string.clod_reliability_battery),
                subtitle = stringResource(R.string.clod_reliability_battery_why),
                iconRes = R.drawable.ic_baseline_battery,
                done = state.batteryIgnored,
                onClick = { onAction(MainAction.ReliabilityAllowBattery) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp))
            ReliabilityStep(
                title = stringResource(R.string.clod_reliability_always_on),
                subtitle = stringResource(R.string.clod_reliability_always_on_why),
                iconRes = R.drawable.ic_baseline_vpn_lock,
                done = state.alwaysOn == true,
                onClick = { onAction(MainAction.ReliabilityOpenVpnSettings) },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    !state.batteryIgnored -> {
                        TextButton(onClick = { onAction(MainAction.ReliabilityDismiss) }) {
                            Text(stringResource(R.string.clod_reliability_later))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onAction(MainAction.ReliabilityAllowBattery) }) {
                            Text(stringResource(R.string.clod_reliability_setup))
                        }
                    }
                    state.alwaysOn != true -> {
                        TextButton(onClick = { onAction(MainAction.ReliabilityDismiss) }) {
                            Text(stringResource(R.string.clod_reliability_later))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onAction(MainAction.ReliabilityOpenVpnSettings) }) {
                            Text(stringResource(R.string.clod_reliability_open_settings))
                        }
                    }
                    else -> {
                        Button(onClick = { onAction(MainAction.ReliabilityDismiss) }) {
                            Text(stringResource(R.string.clod_reliability_ready))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun ReliabilityRows(state: ReliabilityState, onAction: (MainAction) -> Unit) {
    ReliabilityStep(
        title = stringResource(R.string.clod_reliability_battery),
        subtitle = if (state.batteryIgnored) {
            stringResource(R.string.clod_reliability_battery_allowed)
        } else {
            stringResource(R.string.clod_reliability_battery_limited)
        },
        iconRes = R.drawable.ic_baseline_battery,
        done = state.batteryIgnored,
        subtitleDone = state.batteryIgnored,
        onClick = { onAction(MainAction.ReliabilityAllowBattery) },
    )
    ReliabilityStep(
        title = stringResource(R.string.clod_reliability_always_on),
        subtitle = alwaysOnSubtitle(state.alwaysOn),
        iconRes = R.drawable.ic_baseline_vpn_lock,
        done = state.alwaysOn == true,
        subtitleDone = state.alwaysOn == true,
        onClick = { onAction(MainAction.ReliabilityOpenVpnSettings) },
    )
}
