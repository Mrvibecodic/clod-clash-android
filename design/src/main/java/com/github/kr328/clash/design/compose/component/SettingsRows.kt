package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Painter? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = iconTint(enabled),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor(enabled),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor(enabled),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
fun SelectRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    enabled: Boolean = true,
) {
    var picking by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { picking = true }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = iconTint(enabled),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor(enabled),
            )
            options.getOrNull(selectedIndex)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor(enabled),
                )
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(title) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = {
                Column {
                    options.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picking = false

                                    if (index != selectedIndex) onSelect(index)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = index == selectedIndex, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(option)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun iconTint(enabled: Boolean) = if (enabled) {
    MaterialTheme.colorScheme.primary
} else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
}

@Composable
private fun titleColor(enabled: Boolean) = if (enabled) {
    MaterialTheme.colorScheme.onSurface
} else {
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
}

@Composable
private fun subtitleColor(enabled: Boolean) = if (enabled) {
    MaterialTheme.colorScheme.onSurfaceVariant
} else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
}
