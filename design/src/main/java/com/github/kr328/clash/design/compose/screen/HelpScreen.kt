package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.SectionHeader

sealed interface HelpAction {
    data object Back : HelpAction
    data class OpenUrl(val url: String) : HelpAction
}

/**
 * Помощь: куда идти за документацией и где лежит исходный код.
 *
 * Поддержки по самой подписке здесь нет и быть не может — её оказывает тот,
 * кто выдал ссылку. Об этом и говорит подсказка сверху.
 */
@Composable
fun HelpScreen(
    onAction: (HelpAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ActivityScaffold(
        title = stringResource(R.string.help),
        onBack = { onAction(HelpAction.Back) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Notice()

            SectionHeader(stringResource(R.string.document))
            LinkRow(
                title = stringResource(R.string.clash_meta_wiki),
                url = stringResource(R.string.clash_meta_wiki_url),
                onAction = onAction,
            )

            SectionHeader(stringResource(R.string.sources))
            LinkRow(
                title = stringResource(R.string.clash_meta_core),
                url = stringResource(R.string.clash_meta_core_url),
                onAction = onAction,
            )
            LinkRow(
                title = stringResource(R.string.clash_meta_for_android),
                url = stringResource(R.string.meta_github_url),
                onAction = onAction,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Ссылка наружу. Адрес показан целиком: видно, куда уводит нажатие. */
@Composable
private fun LinkRow(title: String, url: String, onAction: (HelpAction) -> Unit) {
    ActionRow(
        title = title,
        subtitle = url,
        // Адрес не обрезаем: в многоточии не видно, куда уводит нажатие.
        subtitleMaxLines = 2,
        icon = painterResource(R.drawable.ic_outline_article),
        onClick = { onAction(HelpAction.OpenUrl(url)) },
    )
}

@Composable
private fun Notice() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_outline_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.clod_help_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
