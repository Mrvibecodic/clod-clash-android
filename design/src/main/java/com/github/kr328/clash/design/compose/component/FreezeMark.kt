package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.screen.MainAction
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.statusContainer
import com.github.kr328.clash.design.compose.theme.statusText
import kotlin.math.cos
import kotlin.math.sin

// clod:freeze — значок-снежинка рядом с пингом: одна иконка, чтобы не
// занимать место; цвет только помогает. Нажатие открывает панель с
// объяснением. С узлом значок ничего не делает: пинг и выбор как были.

@Composable
private fun freezeColor(): Color = ClodTheme.extraColors.delayMedium

// Снежинка рисуется сама, чтобы форма читалась и без цвета
@Composable
private fun FreezeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(13.dp)) {
        val stroke = 1.8.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 - stroke / 2

        for (step in 0 until 3) {
            val angle = Math.toRadians(step * 60.0)
            val dx = (cos(angle) * radius).toFloat()
            val dy = (sin(angle) * radius).toFloat()

            drawLine(
                color = color,
                start = Offset(center.x - dx, center.y - dy),
                end = Offset(center.x + dx, center.y + dy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun FreezePill(
    on: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = freezeColor()
    val title = stringResource(R.string.clod_freeze_title)

    // Зону касания значок расширяет до пальца сам, как любой нажимаемый
    // элемент; место под неё в разметке — решение строки (modifier)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.statusContainer(on))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = title }
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FreezeIcon(color = color.statusText())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeSheet(supportUrl: String, onAction: (MainAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val color = freezeColor()

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.HideFreeze) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FreezeIcon(color = color.statusText(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.clod_freeze_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clod_freeze_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clod_freeze_checked_here),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // «Написать в поддержку» — только если панель прислала адрес
                if (supportUrl.isNotBlank()) {
                    TextButton(onClick = { onAction(MainAction.OpenUrl(supportUrl)) }) {
                        Text(stringResource(R.string.clod_freeze_support))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = { onAction(MainAction.HideFreeze) }) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
