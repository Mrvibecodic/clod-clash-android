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
import androidx.compose.foundation.layout.widthIn
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
import com.github.kr328.clash.design.compose.theme.DelayPillFast
import com.github.kr328.clash.design.compose.theme.DelayPillMedium
import com.github.kr328.clash.design.compose.theme.DelayPillSlow

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
 * в ряду и её надо выделить. В списке узлов идёт заливная пилюля
 * ([DelayPill]) — у неё другой контраст, под белый текст.
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
 * Задержка в списке: заливная пилюля, белый текст на статусном цвете.
 *
 * Заливка вместо колонки цифр: цвет площадью читается с расстояния вытянутой
 * руки, а цветное число 15 sp — нет. Цвета пилюль свои ([DelayPillFast] и
 * соседи), а не роли темы: под белым текстом нужен насыщенный фон в обеих
 * темах.
 *
 * Ширина не меньше 52 dp и текст по центру: у пилюли по месту ширина зависела
 * бы от числа знаков, и правый край списка гулял бы от строки к строке —
 * ровно та болезнь, ради которой раньше была колонка фиксированной ширины.
 */
@Composable
fun DelayPill(delay: Int, modifier: Modifier = Modifier) {
    val unknown = delay <= 0 || delay >= DELAY_UNKNOWN

    if (unknown) {
        Box(
            modifier = modifier
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "—",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        return
    }

    val color = when {
        delay < 100 -> DelayPillFast
        delay < 200 -> DelayPillMedium
        else -> DelayPillSlow
    }

    Box(
        modifier = modifier
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$delay ms",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Строка узла в списке серверов: флаг, название, описание второй строкой,
 * задержка справа.
 *
 * Выбранный узел отмечен полосой слева и заливкой `secondaryContainer`.
 * Роль вместо полупрозрачного primary: у контейнерной роли контраст с фоном
 * посчитан самой схемой в обеих темах, а `primary` при 12 % на светлой теме
 * отличался от `surfaceContainerLow` на считанные единицы яркости — на
 * телефоне при свете выбранная строка не отличалась от соседних. Полоса
 * остаётся: она читается при любой яркости и с расстояния.
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
                    MaterialTheme.colorScheme.secondaryContainer
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
        DelayPill(delay)
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
