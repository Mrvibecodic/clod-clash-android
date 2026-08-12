package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

sealed interface ApkBrokenAction {
    data object Back : ApkBrokenAction

    /** Открыть страницу релизов: адрес берётся из ресурсов, а не приходит извне. */
    data object OpenReleases : ApkBrokenAction
}

/**
 * Экран «Приложение повреждено»: apk собран не полностью или подменён,
 * работать с ним нельзя, и единственное осмысленное действие — поставить
 * приложение заново со страницы релизов.
 *
 * Состояния у экрана нет: он показывает один и тот же текст всегда.
 * Последний экран, живший на XML и старом DSL `preferenceScreen`, — вместе
 * с ним из проекта ушли разметки, `dataBinding` и `kapt`.
 */
@Composable
fun ApkBrokenScreen(
    onAction: (ApkBrokenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ActivityScaffold(
        title = stringResource(R.string.application_broken),
        onBack = { onAction(ApkBrokenAction.Back) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.application_broken_tips),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )

            SectionHeader(title = stringResource(R.string.reinstall))

            ActionRow(
                title = stringResource(R.string.github_releases),
                icon = painterResource(R.drawable.ic_baseline_get_app),
                subtitle = stringResource(R.string.meta_github_url),
                onClick = { onAction(ApkBrokenAction.OpenReleases) },
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}
