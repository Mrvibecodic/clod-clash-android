package com.github.kr328.clash.design.compose.component

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.roundToInt

/**
 * Рисует системный [Drawable] средствами самого `Drawable`.
 *
 * Нужен ровно там, где картинка приходит от системы, а не из ресурсов:
 * иконки установленных приложений отдаёт `PackageManager`, и другого
 * представления у них нет.
 *
 * Очевидный путь — растеризовать в `Bitmap` и показать как `ImageBitmap` —
 * на списке в три сотни приложений стоит по несколько десятков килобайт
 * и одной синхронной отрисовке на КАЖДУЮ иконку, причём заново после
 * каждой прокрутки назад: элемент `LazyColumn` уходит из композиции вместе
 * со своим `remember`. Старый `RecyclerView` ничего не растеризовал —
 * он отдавал `Drawable` прямо во `View`. Здесь то же самое: холст Compose
 * разворачивается в системный и отдаётся `Drawable.draw`.
 *
 * Анимированные `Drawable` кадры менять не будут — подписки на
 * `Drawable.Callback` тут нет намеренно. Иконки приложений статичны,
 * а слежение за кадрами стоило бы отдельного состояния на каждую строку.
 */
class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        } else {
            // Размер неизвестен — пусть его задаёт вызывающая сторона
            // модификатором, иначе картинка схлопнется в точку.
            Size.Unspecified
        }

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            // Границы ставятся перед каждой отрисовкой: один и тот же
            // `Drawable` может рисоваться в разных размерах.
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

@Composable
fun rememberDrawablePainter(drawable: Drawable): Painter =
    remember(drawable) { DrawablePainter(drawable) }
