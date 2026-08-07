package com.github.kr328.clash.design.compose.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.github.kr328.clash.design.R

/**
 * Угол поворота для иконки обновления.
 *
 * Бесконечная анимация создаётся ТОЛЬКО пока [spinning] истинно: иначе кадр
 * перерисовывался бы шестьдесят раз в секунду всё время, что открыт экран,
 * ради неподвижной картинки. Когда ветка меняется, состояние анимации
 * выбрасывается вместе с ней, и следующий запуск начинается с нуля градусов.
 */
@Composable
private fun syncRotation(spinning: Boolean): State<Float> {
    if (!spinning) return remember { mutableFloatStateOf(0f) }

    val transition = rememberInfiniteTransition(label = "sync")

    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            // Линейно и без пауз: любое замедление на краю оборота читается как
            // подвисание, а именно от подозрения «оно вообще живое?» иконка
            // и крутится.
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sync-angle",
    )
}

/** Иконка обновления, которая крутится, пока [spinning]. */
@Composable
fun SyncIcon(
    spinning: Boolean,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // Значение читается ВНУТРИ лямбды graphicsLayer: так кадр анимации
    // пересобирает только слой отрисовки. Прочитай мы `angle.value` здесь,
    // в теле, — иконка рекомпозировалась бы шестьдесят раз в секунду вместе
    // с загрузкой painter'а.
    val angle = syncRotation(spinning)

    Icon(
        painter = painterResource(R.drawable.ic_baseline_sync),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = angle.value },
    )
}

/**
 * Кнопка обновления в шапке.
 *
 * Пока идёт обновление, кнопка выключена — иначе каждое повторное нажатие
 * ставило бы в очередь ещё один поход в сеть за тем же файлом, а человек
 * жмёт повторно именно тогда, когда не понял, идёт ли что-нибудь вообще.
 * Цвет на время работы — фирменный: выключенная кнопка не должна выглядеть
 * недоступной, она выглядит занятой.
 */
@Composable
fun SyncIconButton(
    spinning: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = !spinning, modifier = modifier) {
        SyncIcon(
            spinning = spinning,
            contentDescription = contentDescription,
            tint = if (spinning) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
