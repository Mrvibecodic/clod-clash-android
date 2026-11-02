package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.ProviderValues
import com.github.kr328.clash.design.R
import java.util.UUID

private val idsSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

@Composable
internal fun ProviderValuesDialog(
    name: String,
    profile: UUID,
    groups: List<ProviderValues.Group>,
    onAction: (MainAction) -> Unit,
) {
    val unchecked = rememberSaveable(saver = idsSaver) { mutableStateListOf() }

    val keys = groups.filter { it.id !in unchecked }
        .flatMap { group -> group.lines.flatMap { it.reset } }
        .distinct()

    AlertDialog(
        onDismissRequest = { onAction(MainAction.CloseProviderValues) },
        title = { Text(stringResource(R.string.clod_provider_values)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.clod_provider_values_text, name))
                Spacer(Modifier.height(8.dp))
                groups.forEach { group ->
                    key(group.id) {
                        GroupRow(
                            group = group,
                            checked = group.id !in unchecked,
                            onChecked = { if (it) unchecked.remove(group.id) else unchecked.add(group.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = keys.isNotEmpty(),
                onClick = { onAction(MainAction.ResetProviderValues(profile, keys)) },
            ) {
                Text(stringResource(R.string.clod_provider_values_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(MainAction.CloseProviderValues) }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun GroupRow(
    group: ProviderValues.Group,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(groupTitle(group.id)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = groupSubtitle(group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun groupTitle(id: String): Int = when (id) {
    ProviderValues.GROUP_MODE -> R.string.clod_provider_group_mode
    ProviderValues.GROUP_LOG -> R.string.clod_provider_group_log
    ProviderValues.GROUP_PORTS -> R.string.clod_provider_group_ports
    ProviderValues.GROUP_TUN -> R.string.clod_provider_group_tun
    ProviderValues.GROUP_DNS -> R.string.clod_provider_group_dns
    else -> R.string.clod_provider_group_features
}

@Composable
private fun groupSubtitle(group: ProviderValues.Group): String {
    val count = pluralStringResource(R.plurals.clod_provider_values_count, group.lines.size, group.lines.size)

    return if (group.id != ProviderValues.GROUP_MODE || ProviderValues.MODE in group.lines.first().reset) {
        stringResource(R.string.clod_provider_values_shared, count)
    } else {
        count
    }
}
