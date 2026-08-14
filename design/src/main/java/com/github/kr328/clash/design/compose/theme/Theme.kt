package com.github.kr328.clash.design.compose.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Тема Clod Clash для Compose.
 *
 * Пока интерфейс переезжает с XML на Compose, обе темы живут рядом: XML-экраны берут
 * цвета из `design/res/values/themes.xml`, Compose-экраны — отсюда. Значения совпадают
 * по смыслу (фирменный индиго), поэтому переход между экранами не бросается в глаза.
 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    scrim = LightScrim,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    scrim = DarkScrim,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

/**
 * Цвета, которых нет в [ColorScheme]: состояния подключения и фирменный градиент.
 * Отдаются через CompositionLocal, чтобы экраны читали их так же, как
 * `MaterialTheme.colorScheme`.
 */
@Immutable
data class ClodExtraColors(
    val statusConnected: Color,
    val statusConnecting: Color,
    val statusStopped: Color,
    val brandGradient: Brush,
)

// Значения по умолчанию заданы здесь, а не в конструкторе: цвета из Color.kt
// объявлены internal, и тащить их в значения по умолчанию публичного класса —
// значит светить internal-объявления в публичном API модуля.
private val BrandGradient = Brush.linearGradient(listOf(BrandGradientStart, BrandGradientEnd))

private val LightExtraColors = ClodExtraColors(
    statusConnected = LightStatusConnected,
    statusConnecting = LightStatusConnecting,
    statusStopped = LightStatusStopped,
    brandGradient = BrandGradient,
)

private val DarkExtraColors = ClodExtraColors(
    statusConnected = DarkStatusConnected,
    statusConnecting = DarkStatusConnecting,
    statusStopped = DarkStatusStopped,
    brandGradient = BrandGradient,
)

private val LocalClodExtraColors = staticCompositionLocalOf { LightExtraColors }

object ClodTheme {
    val extraColors: ClodExtraColors
        @Composable get() = LocalClodExtraColors.current
}

/**
 * Корневая тема всех Compose-экранов.
 *
 * @param dynamicColor брать акцент из обоев (Material You). Работает с Android 12;
 *   по умолчанию выключено — фирменный индиго должен совпадать с иконкой.
 */
@Composable
fun ClodClashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalClodExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ClodTypography,
            shapes = ClodShapes,
            content = content,
        )
    }
}
