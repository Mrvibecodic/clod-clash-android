package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.rememberDrawablePainter
import com.github.kr328.clash.design.model.ProfileProvider

@Immutable
data class NewProfileState(
    val providers: List<ProfileProvider> = emptyList(),
)

sealed interface NewProfileAction {
    data object Back : NewProfileAction
    data class Select(val provider: ProfileProvider) : NewProfileAction
    data class Detail(val provider: ProfileProvider) : NewProfileAction
}

/**
 * Экран «Новый профиль»: откуда взять подписку.
 *
 * Первые три строки свои (файл, ссылка, QR), дальше — приложения, умеющие
 * отдать ссылку по намерению `ACTION_PROVIDE_URL`. У них долгое нажатие
 * открывает системные сведения о приложении — так можно понять, что это
 * за строка взялась в списке, и отключить её источник.
 */
@Composable
fun NewProfileScreen(
    state: NewProfileState,
    onAction: (NewProfileAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ActivityScaffold(
        title = stringResource(R.string.create_profile),
        onBack = { onAction(NewProfileAction.Back) },
        modifier = modifier,
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            // Ключ по номеру: у внешних поставщиков нет ничего заведомо
            // уникального — два приложения одного разработчика легко дают
            // одинаковые название и пояснение. Список за время жизни экрана
            // не меняется, так что номер здесь честнее любого поля.
            itemsIndexed(state.providers, key = { index, _ -> index }) { _, provider ->
                ProviderRow(
                    provider = provider,
                    onClick = { onAction(NewProfileAction.Select(provider)) },
                    onLongClick = { onAction(NewProfileAction.Detail(provider)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderRow(
    provider: ProfileProvider,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        val icon = provider.icon

        if (icon == null) {
            Spacer(Modifier.size(32.dp))
        } else {
            Image(
                painter = rememberDrawablePainter(icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = provider.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
