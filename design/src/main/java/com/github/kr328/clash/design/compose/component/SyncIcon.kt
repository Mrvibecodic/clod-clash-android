package com.github.kr328.clash.design.compose.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.statusContainer
import com.github.kr328.clash.design.compose.theme.statusText

@Composable
private fun syncRotation(spinning: Boolean): State<Float> {
    if (!spinning) return remember { mutableFloatStateOf(0f) }

    val transition = rememberInfiniteTransition(label = "sync")

    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sync-angle",
    )
}

@Composable
fun SyncIcon(
    spinning: Boolean,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val angle = syncRotation(spinning)

    Icon(
        painter = painterResource(R.drawable.ic_baseline_sync),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = angle.value },
    )
}

@Composable
fun SyncIconButton(
    spinning: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Подписка давно не обновлялась: кнопка в янтарной обводке с точкой
    highlight: Boolean = false,
) {
    val warn = ClodTheme.extraColors.statusConnecting
    val lit = highlight && !spinning

    IconButton(
        onClick = onClick,
        enabled = !spinning,
        modifier = if (lit) modifier.border(2.dp, warn, CircleShape) else modifier,
        colors = if (lit) {
            IconButtonDefaults.iconButtonColors(
                containerColor = warn.statusContainer(MaterialTheme.colorScheme.background),
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        BadgedBox(badge = { if (lit) Badge(containerColor = warn) }) {
            SyncIcon(
                spinning = spinning,
                contentDescription = contentDescription,
                tint = when {
                    spinning -> MaterialTheme.colorScheme.primary
                    lit -> warn.statusText()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
