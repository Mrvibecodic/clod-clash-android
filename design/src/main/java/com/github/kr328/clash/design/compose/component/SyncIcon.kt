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
