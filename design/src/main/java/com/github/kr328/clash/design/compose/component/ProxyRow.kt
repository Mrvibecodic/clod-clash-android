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
import com.github.kr328.clash.design.compose.theme.statusContainer

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

@Composable
private fun delayColor(delay: Int): Color = when {
    delay <= 0 || delay >= DELAY_UNKNOWN -> ClodTheme.extraColors.statusStopped
    delay < 200 -> ClodTheme.extraColors.statusConnected
    delay < 400 -> ClodTheme.extraColors.statusConnecting
    else -> MaterialTheme.colorScheme.error
}

@Composable
fun PingBadge(delay: Int, marksOnly: Boolean = false, modifier: Modifier = Modifier) {
    val color: Color
    val label: String

    if (marksOnly) {
        when {
            delay <= 0 -> {
                color = ClodTheme.extraColors.statusStopped
                label = "—"
            }
            delay >= DELAY_UNKNOWN -> {
                color = MaterialTheme.colorScheme.error
                label = "✕"
            }
            else -> {
                color = ClodTheme.extraColors.statusConnected
                label = "✓"
            }
        }
    } else {
        val unknown = delay <= 0 || delay >= DELAY_UNKNOWN

        color = delayColor(delay)
        label = if (unknown) "—" else "$delay ms"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.statusContainer())
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun DelayPill(delay: Int, marksOnly: Boolean = false, modifier: Modifier = Modifier) {
    if (marksOnly && delay > 0) {
        val failed = delay >= DELAY_UNKNOWN

        Box(
            modifier = modifier
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(50))
                .background(if (failed) DelayPillSlow else DelayPillFast)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (failed) "✕" else "✓",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        return
    }

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
        delay < 200 -> DelayPillFast
        delay < 400 -> DelayPillMedium
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
) {
    val (flag, name) = splitFlag(title)

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
        DelayPill(delay, marksOnly)
        Spacer(Modifier.width(6.dp))
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
