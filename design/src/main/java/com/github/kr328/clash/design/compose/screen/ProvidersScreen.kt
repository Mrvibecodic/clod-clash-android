package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.util.elapsedIntervalString
import com.github.kr328.clash.design.util.type

/**
 * Строка списка провайдеров.
 *
 * Пришла на смену `ProviderState` на `BaseObservable`: там изменяемые поля
 * с `notifyPropertyChanged` были нужны разметке, а здесь список целиком
 * лежит в состоянии экрана и меняется копией.
 */
data class ProviderRow(
    val provider: Provider,
    val updatedAt: Long,
    val updating: Boolean = false,
) {
    /**
     * Встроенный провайдер описан прямо в конфигурации: обновлять его неоткуда
     * и незачем, поэтому у него нет ни кнопки, ни отметки времени.
     */
    val inline: Boolean
        get() = provider.vehicleType == Provider.VehicleType.Inline
}

data class ProvidersState(
    val providers: List<ProviderRow> = emptyList(),
    val currentTime: Long = 0,
)

sealed interface ProvidersAction {
    data object Back : ProvidersAction
    data object UpdateAll : ProvidersAction
    data class Update(val index: Int) : ProvidersAction
}

/** Экран «Провайдеры»: наборы правил и узлов, приходящие отдельными файлами. */
@Composable
fun ProvidersScreen(
    state: ProvidersState,
    onAction: (ProvidersAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    ActivityScaffold(
        title = stringResource(R.string.providers),
        onBack = { onAction(ProvidersAction.Back) },
        modifier = modifier,
        actions = {
            // Обновлять нечего, если все провайдеры встроенные.
            if (state.providers.any { !it.inline }) {
                IconButton(onClick = { onAction(ProvidersAction.UpdateAll) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_sync),
                        contentDescription = stringResource(R.string.update_all),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            // Ключ — ПАРА тип+имя, а не одно имя: список склеен из двух карт
            // ядра (`tunnel.Providers` и `tunnel.RuleProviders`), имена
            // уникальны внутри каждой, но не между ними. Конфиг с одинаково
            // названными proxy- и rule-провайдером — обычное дело, и на одном
            // имени `LazyColumn` уронил бы экран прямо в композиции.
            itemsIndexed(
                state.providers,
                key = { _, it -> "${it.provider.type}/${it.provider.name}" },
            ) { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.provider.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = row.provider.type(context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!row.inline) {
                        Text(
                            text = (state.currentTime - row.updatedAt)
                                .coerceAtLeast(0)
                                .elapsedIntervalString(context),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (row.updating) {
                            // Кружок занимает ровно место кнопки (48 dp):
                            // 13 + 22 + 13, иначе строка дёргалась бы вбок
                            // на каждом обновлении.
                            Spacer(Modifier.width(13.dp))
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(13.dp))
                        } else {
                            IconButton(onClick = { onAction(ProvidersAction.Update(index)) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_baseline_swap_vert),
                                    contentDescription = stringResource(R.string.more),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    } else {
                        // У встроенного провайдера справа пусто — без отступа
                        // название упёрлось бы в край экрана.
                        Spacer(Modifier.width(18.dp))
                    }
                }
            }
        }
    }
}
