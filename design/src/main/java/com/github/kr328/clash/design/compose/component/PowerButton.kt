package com.github.kr328.clash.design.compose.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.TimerTextStyle
import com.github.kr328.clash.design.model.ToggleIntent
import com.github.kr328.clash.design.model.enabled
import com.github.kr328.clash.design.model.label

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
}


private const val FACE_HIGHLIGHT = 0.22f

private val POWER_DIAMETER = 134.dp

internal fun powerFaceCenter(accent: Color, dark: Boolean): Color =
    lerp(accent, if (dark) Color.White else Color.Black, FACE_HIGHLIGHT)

@Composable
private fun powerGlow(animated: Boolean): State<Float> {
    if (!animated) return remember { mutableFloatStateOf(0f) }

    val infinite = rememberInfiniteTransition(label = "powerGlow")

    return infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "powerGlowAlpha",
    )
}

@Composable
fun PowerButton(
    status: ConnectionStatus,
    intent: ToggleIntent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val extra = ClodTheme.extraColors

    val haptic = LocalHapticFeedback.current

    val scheme = MaterialTheme.colorScheme
    val off = status == ConnectionStatus.Disconnected

    val accent = when (status) {
        ConnectionStatus.Disconnected -> scheme.surfaceContainer
        ConnectionStatus.Connecting -> extra.statusConnecting
        ConnectionStatus.Connected -> extra.statusConnected
        ConnectionStatus.Disconnecting -> extra.statusConnecting
    }
    val animatedAccent by animateColorAsState(accent, label = "powerAccent")
    val iconTint by animateColorAsState(if (off) scheme.primary else extra.onStatus, label = "powerIcon")
    val ringAlpha by animateFloatAsState(if (off) 1f else 0f, label = "powerRing")
    val ringOuter = scheme.secondaryContainer
    val ringInner = scheme.outlineVariant

    val glow = powerGlow(!off)

    val faceBrush = remember(animatedAccent, extra.dark, off) {
        if (off) {
            SolidColor(animatedAccent)
        } else {
            Brush.radialGradient(
                colors = listOf(
                    powerFaceCenter(animatedAccent, extra.dark),
                    animatedAccent,
                ),
            )
        }
    }

    Box(
        modifier = modifier
            .size(POWER_DIAMETER)
            .drawBehind {
                val alpha = glow.value
                if (alpha <= 0f) return@drawBehind
                val radius = size.minDimension / 2f * 1.45f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(animatedAccent.copy(alpha = alpha), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = radius,
                    ),
                    radius = radius,
                )
            }
            .clip(CircleShape)
            .background(faceBrush)
            .drawWithContent {
                drawContent()
                val alpha = ringAlpha
                if (alpha <= 0f) return@drawWithContent
                val radius = size.minDimension / 2f
                drawCircle(
                    color = ringOuter,
                    radius = radius - 1.5.dp.toPx(),
                    alpha = alpha,
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawCircle(
                    color = ringInner,
                    radius = radius - 10.dp.toPx(),
                    alpha = alpha,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            .semantics { if (!intent.enabled) disabled() }
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_power),
                contentDescription = stringResource(intent.label()),
                tint = iconTint,
                modifier = Modifier.size(POWER_DIAMETER * if (caption == null) 0.34f else 0.30f),
            )
            if (caption != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = caption,
                    style = TimerTextStyle,
                    color = extra.onStatus,
                )
            }
        }
    }
}
