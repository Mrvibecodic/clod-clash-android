package com.github.kr328.clash.design.compose.screen

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.SubScreenScaffold

@Immutable
data class GeoFileState(
    val name: String,
    val sizeBytes: Long,
    val updatedAt: Long,
)

@Immutable
data class ProviderFileState(
    val name: String,
    val updatedAt: Long,
)

@Immutable
data class RoutingDataState(
    val files: List<GeoFileState> = emptyList(),
    val providers: List<ProviderFileState> = emptyList(),
    val updating: Boolean = false,
)

@Composable
fun RoutingDataScreen(
    state: RoutingDataState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubScreenScaffold(
        title = stringResource(R.string.clod_data_title),
        onBack = { onAction(MainAction.CloseSubScreen) },
        modifier = modifier,
        actions = {
            if (state.updating) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(horizontal = 13.dp)
                        .size(22.dp),
                )
            } else {
                IconButton(onClick = { onAction(MainAction.UpdateRoutingData) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_sync),
                        contentDescription = stringResource(R.string.update_all),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) {
        Text(
            text = stringResource(R.string.clod_geo_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )

        SectionHeader(stringResource(R.string.clod_geo_title))

        state.files.forEach { file ->
            GeoFileRow(file)
        }

        if (state.providers.isNotEmpty()) {
            SectionHeader(stringResource(R.string.providers))

            state.providers.forEach { provider ->
                DataRow(
                    icon = R.drawable.ic_baseline_swap_vertical_circle,
                    title = provider.name,
                    subtitle = relativeTime(provider.updatedAt),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GeoFileRow(file: GeoFileState) {
    val context = LocalContext.current
    val exists = file.sizeBytes > 0

    DataRow(
        icon = R.drawable.ic_baseline_domain,
        title = file.name,
        subtitle = if (exists) {
            relativeTime(file.updatedAt)
        } else {
            stringResource(R.string.clod_geo_missing)
        },
        trailing = if (exists) {
            Formatter.formatShortFileSize(context, file.sizeBytes)
        } else {
            null
        },
    )
}

@Composable
private fun relativeTime(millis: Long): String {
    if (millis <= 0) return stringResource(R.string.clod_never)

    val now = System.currentTimeMillis()

    if (now - millis < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.clod_just_now)

    return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
private fun DataRow(icon: Int, title: String, subtitle: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
