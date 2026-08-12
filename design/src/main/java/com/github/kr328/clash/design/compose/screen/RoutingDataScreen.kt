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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.SubScreenScaffold

/**
 * Один файл маршрутизации на диске.
 *
 * @param sizeBytes 0 — файла нет: до первого запуска он ещё не распакован
 *   из assets, и показывать вместо размера «0 Б» было бы неправдой.
 * @param updatedAt время последней записи в миллисекундах; 0 — файла нет.
 */
@Immutable
data class GeoFileState(
    val name: String,
    val sizeBytes: Long,
    val updatedAt: Long,
)

@Immutable
data class RoutingDataState(
    val files: List<GeoFileState> = emptyList(),
    val updating: Boolean = false,
)

/**
 * «Данные маршрутизации» — списки стран, сайтов и сетей, по которым ядро решает,
 * что пускать через туннель, а что мимо.
 *
 * Это данные, а не код: их можно обновить отдельно от приложения. Само ядро так
 * не умеет — оно вкомпилировано в APK и меняется только с новой версией.
 */
@Composable
fun RoutingDataScreen(
    state: RoutingDataState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubScreenScaffold(
        title = stringResource(R.string.clod_geo_title),
        onBack = { onAction(MainAction.CloseSubScreen) },
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.clod_geo_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(8.dp))

        state.files.forEach { file ->
            GeoFileRow(file)
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { onAction(MainAction.UpdateRoutingData) },
            enabled = !state.updating,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        ) {
            if (state.updating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(stringResource(R.string.clod_geo_update))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GeoFileRow(file: GeoFileState) {
    val context = LocalContext.current
    val exists = file.sizeBytes > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_domain),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (exists) {
                    // Относительное время вместо даты: важно не «когда именно»,
                    // а «давно ли» — файл считается устаревшим, а не просроченным.
                    DateUtils.getRelativeTimeSpanString(
                        file.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                } else {
                    stringResource(R.string.clod_geo_missing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (exists) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = Formatter.formatShortFileSize(context, file.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
