package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.theme.ClodRowCorner
import com.github.kr328.clash.design.compose.theme.ClodTheme

/**
 * Отделяет ведущий флаг-эмодзи от названия узла.
 *
 * Панель отдаёт имена вида «🇳🇱 Нидерланды — Амстердам 1»: флаг уже внутри строки.
 * Своей таблицы «код страны → картинка» не заводим — на Android эмодзи-флаги
 * рендерятся системой, а локальные SVG (как на десктопе) тянули бы за собой
 * сотни файлов и сопоставление имени со страной, которого панель не гарантирует.
 *
 * Флаг — это пара regional indicator symbols (U+1F1E6…U+1F1FF). Идём по кодовым
 * точкам вручную: `String.codePoints()` появился только в API 24, а minSdk у нас 23.
 */
fun splitFlag(title: String): Pair<String?, String> {
    var i = 0
    val flag = StringBuilder()
    while (i < title.length) {
        val cp = title.codePointAt(i)
        if (cp in 0x1F1E6..0x1F1FF) {
            flag.appendCodePoint(cp)
            i += Character.charCount(cp)
        } else {
            break
        }
    }
    if (flag.isEmpty()) return null to title
    val rest = title.substring(i).trimStart(' ', '\u00A0', '\u2009', '·', '-', '—')
    return flag.toString() to rest
}

/**
 * Задержка, которой нет.
 *
 * Ядро отдаёт `0xffff` (65535) и для узла, который ещё не проверяли, и для
 * того, который не ответил: `LastDelayForTestUrl` в mihomo возвращает
 * максимум `uint16`. В модели значение оставлено как есть — на нём держится
 * сортировка «сначала быстрые», с нулём непроверенные уехали бы в начало
 * списка. Разбирается оно здесь.
 *
 * Ноль приходит с другой стороны: пока туннель не поднят, список собирается
 * из файла подписки, и задержки там нет вовсе.
 */
private const val DELAY_UNKNOWN = 0xffff

/**
 * Цвет задержки. Пороги те же, что в макете: до 100 мс зелёный, до 200 —
 * янтарный, дальше красный. Нет данных — серый.
 */
@Composable
private fun delayColor(delay: Int): Color = when {
    delay <= 0 || delay >= DELAY_UNKNOWN -> ClodTheme.extraColors.statusStopped
    delay < 100 -> ClodTheme.extraColors.statusConnected
    delay < 200 -> ClodTheme.extraColors.statusConnecting
    else -> MaterialTheme.colorScheme.error
}

/**
 * Бейдж задержки. Нет данных — прочерк: и «0 ms», и «65535 ms» человек читает
 * как измеренное значение.
 *
 * Остаётся для строки сервера на главном экране, где задержка стоит одна
 * в ряду и её надо выделить. В списке узлов вместо бейджа идёт колонка
 * ([DelayColumn]): шесть разноцветных пилюль подряд спорят друг с другом
 * и с отметкой выбранного узла.
 */
@Composable
fun PingBadge(delay: Int, modifier: Modifier = Modifier) {
    val unknown = delay <= 0 || delay >= DELAY_UNKNOWN
    val color = delayColor(delay)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (unknown) "—" else "$delay ms",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Задержка в списке: число крупно, единица подписью под ним.
 *
 * Колонка фиксированной ширины, выровненная по правому краю: у бейджа ширина
 * зависела от числа знаков, и правый край списка гулял на десяток точек от
 * строки к строке — глазу приходилось искать значение заново в каждой.
 */
@Composable
fun DelayColumn(delay: Int, modifier: Modifier = Modifier) {
    val unknown = delay <= 0 || delay >= DELAY_UNKNOWN
    val color = delayColor(delay)

    Column(
        modifier = modifier.width(38.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = if (unknown) "—" else "$delay",
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!unknown) {
            Text(
                text = "ms",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Строка узла в списке серверов: флаг, название, описание второй строкой,
 * задержка справа.
 *
 * Выбранный узел отмечен полосой слева и заливкой. Одной заливки не хватало:
 * `primary` при 12 % на светлой теме отличается от `surfaceContainerLow`
 * на считанные единицы яркости, и на телефоне при свете выбранная строка
 * от соседних не отличалась вовсе. Полоса читается при любой яркости и
 * не мешает бейджу — галочка в конце строки мешала бы.
 */
@Composable
fun ProxyRow(
    title: String,
    subtitle: String,
    delay: Int,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (flag, name) = splitFlag(title)

    // Выбор узла и звезда — короткий отклик в палец. Ряд узкий, соседние
    // строки близко, и подтверждение «нажалось именно это» стоит дешевле,
    // чем взгляд на список после каждого касания.
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(ClodRowCorner))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                onClick()
            }
            .padding(end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Отметка выбранного узла. Место под неё занято всегда: появись полоса
        // по месту, весь текст строки прыгал бы вправо на каждое переключение.
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ),
        )
        Spacer(Modifier.width(8.dp))
        if (flag != null) {
            Text(text = flag, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        DelayColumn(delay)
        Spacer(Modifier.width(6.dp))
        // Звезда — отдельная зона нажатия справа от задержки, как на ПК.
        // Долгое нажатие по строке для этого не годится: на строке уже висит
        // выбор узла, и человек, промахнувшись длительностью, переключил бы
        // сервер вместо отметки.
        // Не `IconButton`: он тянет за собой минимальный размер зоны нажатия
        // в 48 dp и раздувает строку списка. Зона в 32 dp пальцем берётся,
        // а высота строки остаётся та же, что была до появления звезды.
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                    onToggleFavorite()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline,
                ),
                contentDescription = stringResource(
                    if (favorite) R.string.clod_favorite_remove else R.string.clod_favorite_add,
                ),
                tint = if (favorite) {
                    ClodTheme.extraColors.statusConnecting
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
