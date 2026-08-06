package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.github.kr328.clash.design.R

/**
 * Каркас отдельного экрана-активити на Compose: шапка с названием, стрелкой
 * назад и кнопками справа.
 *
 * Заменяет связку `ActivityBarLayout` + `common_activity_bar.xml`, которую
 * каждый XML-экран подключал у себя и сам раздавал отступы под системные
 * панели. `Scaffold` делает это сам, поэтому вычислять их вручную больше
 * не нужно — экран получает готовый [PaddingValues].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_arrow_back),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        content = content,
    )
}
