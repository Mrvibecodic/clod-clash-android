package com.github.kr328.clash.design.compose.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.TimerTextStyle

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
}

@Composable
fun PowerButton(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: androidx.compose.ui.unit.Dp = 134.dp,
    caption: String? = null,
) {
    val extra = ClodTheme.extraColors

    val haptic = LocalHapticFeedback.current

    val accent = when (status) {
        ConnectionStatus.Disconnected -> extra.statusStopped
        ConnectionStatus.Connecting -> extra.statusConnecting
        ConnectionStatus.Connected -> extra.statusConnected
    }
    val animatedAccent by animateColorAsState(accent, label = "powerAccent")
    val animatedDiameter by animateDpAsState(
        targetValue = diameter,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "powerDiameter",
    )

    val animated = status != ConnectionStatus.Disconnected
    val infinite = rememberInfiniteTransition(label = "powerGlow")
    val glow by infinite.animateFloat(
        initialValue = if (animated) 0.25f else 0f,
        targetValue = if (animated) 0.55f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "powerGlowAlpha",
    )

    Box(
        modifier = modifier
            .size(animatedDiameter)
            .drawBehind {
                if (glow <= 0f) return@drawBehind
                val radius = size.minDimension / 2f * 1.45f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(animatedAccent.copy(alpha = glow), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = radius,
                    ),
                    radius = radius,
                )
            }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        lerp(animatedAccent, Color.White, 0.45f),
                        animatedAccent,
                    ),
                ),
            )
            .clickable(enabled = enabled, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_power),
                contentDescription = stringResource(
                    if (status == ConnectionStatus.Connected) {
                        R.string.clod_action_disconnect
                    } else {
                        R.string.clod_action_connect
                    },
                ),
                tint = Color.White,
                modifier = Modifier.size(animatedDiameter * if (caption == null) 0.34f else 0.30f),
            )
            if (caption != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = caption,
                    style = TimerTextStyle,
                    color = Color.White,
                )
            }
        }
    }
}
