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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.screen.MainAction
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.statusContainer
import com.github.kr328.clash.design.compose.theme.statusText
import kotlin.math.cos
import kotlin.math.sin

// clod:freeze — пометка «режется» / «не отвечает» рядом с пингом: форма +
// слово, цвет только помогает. Нажатие открывает панель с объяснением.
// С узлом пометка ничего не делает: пинг и выбор как были.

const val FREEZE_FROZEN = "frozen"
const val FREEZE_DEAD = "dead"

@Composable
private fun freezeColor(mark: String): Color =
    if (mark == FREEZE_DEAD) ClodTheme.extraColors.delaySlow else ClodTheme.extraColors.delayMedium

@Composable
fun freezeWord(mark: String): String =
    stringResource(if (mark == FREEZE_DEAD) R.string.clod_freeze_dead else R.string.clod_freeze_frozen)

@Composable
private fun freezeTitle(mark: String): String =
    stringResource(if (mark == FREEZE_DEAD) R.string.clod_freeze_dead_title else R.string.clod_freeze_frozen_title)

@Composable
private fun freezeExplain(mark: String): String =
    stringResource(if (mark == FREEZE_DEAD) R.string.clod_freeze_dead_text else R.string.clod_freeze_frozen_text)

@Composable
fun freezeCurrentLine(mark: String): String =
    stringResource(if (mark == FREEZE_DEAD) R.string.clod_freeze_dead_current else R.string.clod_freeze_frozen_current)

// Снежинка (режется) или перечёркнутый круг (не отвечает) — рисуются сами,
// чтобы форма читалась и без цвета
@Composable
private fun FreezeIcon(mark: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(13.dp)) {
        val stroke = 1.8.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 - stroke / 2

        if (mark == FREEZE_DEAD) {
            drawCircle(color = color, radius = radius, center = center, style = Stroke(stroke))

            val leg = radius * 0.7f

            drawLine(
                color = color,
                start = Offset(center.x - leg, center.y - leg),
                end = Offset(center.x + leg, center.y + leg),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        } else {
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
}

@Composable
fun FreezePill(
    mark: String,
    on: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val color = freezeColor(mark)
    val word = freezeWord(mark)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.statusContainer(on))
            .clickable(role = Role.Button, onClickLabel = freezeTitle(mark), onClick = onClick)
            .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FreezeIcon(mark = mark, color = color.statusText())
        if (!compact) {
            Text(
                text = word,
                color = color.statusText(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeSheet(mark: String, supportUrl: String, onAction: (MainAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val color = freezeColor(mark)

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
                FreezeIcon(mark = mark, color = color.statusText(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = freezeTitle(mark),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = freezeExplain(mark),
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
                Button(
                    onClick = {
                        onAction(MainAction.HideFreeze)
                        onAction(MainAction.SelectTab(MainTab.Servers))
                    },
                ) {
                    Text(stringResource(R.string.clod_freeze_pick_another))
                }
            }
        }
    }
}
