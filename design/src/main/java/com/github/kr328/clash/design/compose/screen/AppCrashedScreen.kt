package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold

@Immutable
data class AppCrashedState(
    val logs: String = "",
)

sealed interface AppCrashedAction {
    data object Back : AppCrashedAction
}

/**
 * Экран «Приложение упало»: системный лог падения.
 *
 * Текст выделяемый — его сюда и показывают затем, чтобы человек мог скопировать
 * и переслать. Моноширинный шрифт: в трассировке стека столбцы имеют смысл.
 */
@Composable
fun AppCrashedScreen(
    state: AppCrashedState,
    onAction: (AppCrashedAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ActivityScaffold(
        title = stringResource(R.string.application_crashed),
        onBack = { onAction(AppCrashedAction.Back) },
        modifier = modifier,
    ) { padding ->
        SelectionContainer {
            Text(
                text = state.logs,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}
