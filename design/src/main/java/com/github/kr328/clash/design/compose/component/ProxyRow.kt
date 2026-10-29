package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.bidiIsolated
import com.github.kr328.clash.design.compose.theme.ClodRowCorner
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.statusContainer
import com.github.kr328.clash.design.compose.theme.statusText
import com.github.kr328.clash.service.model.PanelInfo

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

private const val DELAY_UNKNOWN = 0xffff

@Immutable
data class PingBounds(val fast: Int = 200, val medium: Int = 400)

fun PanelInfo?.pingBounds(): PingBounds =
    if (this != null && pingFast > 0 && pingMedium > pingFast) PingBounds(pingFast, pingMedium) else PingBounds()

enum class DelayMark { Untested, Dead, Fast, Medium, Slow }

// Один ответ на всё, что показывает задержку: 0 — узел не мерили (серый прочерк),
// 0xffff — мерили и он не ответил, тайм-аут или ошибка (красный, как на ПК).
fun delayMark(delay: Int, bounds: PingBounds): DelayMark = when {
    delay <= 0 -> DelayMark.Untested
    delay >= DELAY_UNKNOWN -> DelayMark.Dead
    delay < bounds.fast -> DelayMark.Fast
    delay < bounds.medium -> DelayMark.Medium
    else -> DelayMark.Slow
}

@Composable
private fun delayLabel(mark: DelayMark, delay: Int, marksOnly: Boolean): String = when {
    mark == DelayMark.Dead -> if (marksOnly) "✕" else "—"
    marksOnly -> "✓"
    else -> stringResource(R.string.clod_delay_ms, delay)
}

@Composable
fun DelayPill(
    delay: Int,
    on: Color,
    marksOnly: Boolean = false,
    bounds: PingBounds = PingBounds(),
    modifier: Modifier = Modifier,
) {
    val mark = delayMark(delay, bounds)

    if (mark == DelayMark.Untested) {
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
        mark == DelayMark.Dead -> ClodTheme.extraColors.delaySlow
        marksOnly || mark == DelayMark.Fast -> ClodTheme.extraColors.delayFast
        mark == DelayMark.Medium -> ClodTheme.extraColors.delayMedium
        else -> ClodTheme.extraColors.delaySlow
    }

    Box(
        modifier = modifier
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .background(color.statusContainer(on))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = delayLabel(mark, delay, marksOnly),
            color = color.statusText(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ProxyRow(
    title: String,
    subtitle: String,
    delay: Int,
    marksOnly: Boolean,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    pingBounds: PingBounds = PingBounds(),
    pinned: Boolean = false,
    // clod:freeze — значок-снежинка рядом с пингом, пинг как был
    freeze: Boolean = false,
    onFreezeClick: () -> Unit = {},
    // Сервер только для мобильной сети (clod-mobile-only); виден он только в ней
    mobileOnly: Boolean = false,
    group: Boolean = false,
) {
    val (flag, name) = remember(title) { splitFlag(title) }

    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ClodRowCorner))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .selectable(selected = selected, role = Role.RadioButton) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                onClick()
            }
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flag != null) {
            Text(text = flag, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
        } else if (group) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_flash_on),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        val on = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name.bidiIsolated(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val caption = listOfNotNull(
                stringResource(R.string.clod_proxy_pinned).takeIf { pinned },
                subtitle.takeIf { it.isNotBlank() }?.bidiIsolated(),
            ).joinToString(" · ")
            // «Моб. сеть» — во второй строке: справа, рядом с пингом и
            // звездой, она съедала имя сервера на узком экране
            if (caption.isNotEmpty() || mobileOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mobileOnly) {
                        MobilePill(on = on)
                        if (caption.isNotEmpty()) Spacer(Modifier.width(6.dp))
                    }
                    if (caption.isNotEmpty()) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (freeze) {
            // Без слова значок мал для пальца, а промах выбрал бы сервер под ним:
            // место под зону касания — как у звезды, высоту строки это не меняет
            FreezePill(on = on, onClick = onFreezeClick, modifier = Modifier.minimumInteractiveComponentSize())
            Spacer(Modifier.width(6.dp))
        }
        DelayPill(
            delay = delay,
            on = on,
            marksOnly = marksOnly,
            bounds = pingBounds,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .clickable(role = Role.Button) {
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

@Composable
private fun MobilePill(on: Color) {
    val color = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.statusContainer(on))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_signal_bars),
            contentDescription = null,
            tint = color.statusText(),
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.clod_proxy_mobile_only),
            color = color.statusText(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
