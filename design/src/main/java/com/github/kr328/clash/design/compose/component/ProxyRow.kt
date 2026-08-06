package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Бейдж задержки. Пороги те же, что в макете: до 100 мс зелёный, до 200 —
 * янтарный, дальше красный. Ноль означает «не проверялся или недоступен» —
 * показываем прочерк, а не «0 ms», иначе узел выглядит самым быстрым.
 */
@Composable
fun PingBadge(delay: Int, modifier: Modifier = Modifier) {
    val extra = ClodTheme.extraColors
    val color = when {
        delay <= 0 -> extra.statusStopped
        delay < 100 -> extra.statusConnected
        delay < 200 -> extra.statusConnecting
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (delay <= 0) "—" else "$delay ms",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Строка узла в списке серверов: флаг, название, тип второй строкой, задержка справа.
 * Выбранный узел выделен заливкой, а не галочкой — галочка в конце строки конфликтует
 * с бейджем задержки, который в макете стоит ровно там.
 */
@Composable
fun ProxyRow(
    title: String,
    subtitle: String,
    delay: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (flag, name) = splitFlag(title)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Spacer(Modifier.width(8.dp))
        PingBadge(delay)
    }
}
