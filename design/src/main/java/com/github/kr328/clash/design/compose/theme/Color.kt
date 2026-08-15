package com.github.kr328.clash.design.compose.theme

import androidx.compose.ui.graphics.Color

/**
 * Палитра Clod Clash.
 *
 * Источник — фирменный индиго #4F46E5 из иконки приложения (тот же, что в начале
 * градиента логотипа). Схемы посчитаны алгоритмом Material 3:
 *  * акцентные роли (primary/secondary/tertiary) — по схеме Vibrant, она сохраняет
 *    насыщенность источника, а TonalSpot увела бы индиго в серо-сиреневый;
 *  * нейтральные роли (фоны, поверхности, контуры) — по схеме TonalSpot, чтобы фон
 *    оставался почти нейтральным, как в утверждённом макете, а не лиловым;
 *  * роли ошибки — базовые токены Material 3, они откалиброваны и от бренда не зависят.
 *
 * Правка руками ровно одна: LightPrimary принудительно равен #4F46E5, чтобы кнопка
 * подключения и иконка приложения были одного цвета пиксель в пиксель.
 */

// Светлая схема
internal val LightPrimary = Color(0xFF4F46E5)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFF9795FF)
internal val LightOnPrimaryContainer = Color(0xFF14007E)
internal val LightSecondary = Color(0xFF6249B2)
internal val LightOnSecondary = Color(0xFFF7F0FF)
internal val LightSecondaryContainer = Color(0xFFD8CAFF)
internal val LightOnSecondaryContainer = Color(0xFF4E339C)
internal val LightTertiary = Color(0xFF983772)
internal val LightOnTertiary = Color(0xFFFFEFF4)
internal val LightTertiaryContainer = Color(0xFFFD8BCA)
internal val LightOnTertiaryContainer = Color(0xFF610244)
internal val LightInversePrimary = Color(0xFF8582FF)
internal val LightBackground = Color(0xFFFCF8FE)
internal val LightOnBackground = Color(0xFF32313B)
internal val LightSurface = Color(0xFFFCF8FE)
internal val LightOnSurface = Color(0xFF32313B)
internal val LightSurfaceVariant = Color(0xFFE4E1ED)
internal val LightOnSurfaceVariant = Color(0xFF5F5E68)
internal val LightOutline = Color(0xFF7B7984)
internal val LightOutlineVariant = Color(0xFFB3B0BC)
internal val LightInverseSurface = Color(0xFF0E0E12)
internal val LightInverseOnSurface = Color(0xFF9E9CA2)
internal val LightScrim = Color(0xFF000000)
internal val LightSurfaceDim = Color(0xFFDCD8E4)
internal val LightSurfaceBright = Color(0xFFFCF8FE)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF6F2FA)
internal val LightSurfaceContainer = Color(0xFFF0ECF6)
internal val LightSurfaceContainerHigh = Color(0xFFEAE6F1)
internal val LightSurfaceContainerHighest = Color(0xFFE4E1ED)
internal val LightError = Color(0xFFB3261E)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFF9DEDC)
internal val LightOnErrorContainer = Color(0xFF410E0B)

// Тёмная схема
internal val DarkPrimary = Color(0xFFA7A5FF)
internal val DarkOnPrimary = Color(0xFF1C00A0)
internal val DarkPrimaryContainer = Color(0xFF9795FF)
internal val DarkOnPrimaryContainer = Color(0xFF14007E)
internal val DarkSecondary = Color(0xFFA98FFD)
internal val DarkOnSecondary = Color(0xFF280072)
internal val DarkSecondaryContainer = Color(0xFF4D329B)
internal val DarkOnSecondaryContainer = Color(0xFFD6C9FF)
internal val DarkTertiary = Color(0xFFFF9DD1)
internal val DarkOnTertiary = Color(0xFF6C0F4D)
internal val DarkTertiaryContainer = Color(0xFFFA88C8)
internal val DarkOnTertiaryContainer = Color(0xFF5E0042)
internal val DarkInversePrimary = Color(0xFF4D44E6)
internal val DarkBackground = Color(0xFF0E0E12)
internal val DarkOnBackground = Color(0xFFE7E4F0)
internal val DarkSurface = Color(0xFF0E0E12)
internal val DarkOnSurface = Color(0xFFE7E4F0)
internal val DarkSurfaceVariant = Color(0xFF25252E)
internal val DarkOnSurfaceVariant = Color(0xFFACA9B5)
internal val DarkOutline = Color(0xFF76747F)
internal val DarkOutlineVariant = Color(0xFF484750)
internal val DarkInverseSurface = Color(0xFFFCF8FE)
internal val DarkInverseOnSurface = Color(0xFF56545A)
internal val DarkScrim = Color(0xFF000000)
internal val DarkSurfaceDim = Color(0xFF0E0E12)
internal val DarkSurfaceBright = Color(0xFF2C2B34)
internal val DarkSurfaceContainerLowest = Color(0xFF000000)
internal val DarkSurfaceContainerLow = Color(0xFF131318)
internal val DarkSurfaceContainer = Color(0xFF191920)
internal val DarkSurfaceContainerHigh = Color(0xFF1F1F26)
internal val DarkSurfaceContainerHighest = Color(0xFF25252E)
internal val DarkError = Color(0xFFF2B8B5)
internal val DarkOnError = Color(0xFF601410)
internal val DarkErrorContainer = Color(0xFF8C1D18)
internal val DarkOnErrorContainer = Color(0xFFF9DEDC)

// Токены вне ColorScheme: состояния подключения и фирменный градиент.
// Значения зелёного/янтарного/красного взяты из утверждённого макета.
internal val LightStatusConnected = Color(0xFF16A34A)
internal val DarkStatusConnected = Color(0xFF4ADE80)
internal val LightStatusConnecting = Color(0xFFD97706)
internal val DarkStatusConnecting = Color(0xFFFBBF24)
internal val LightStatusStopped = Color(0xFF6B7280)
internal val DarkStatusStopped = Color(0xFF9CA3AF)

/** Начало и конец градиента логотипа: индиго → фиолетовый. */
internal val BrandGradientStart = Color(0xFF4F46E5)
internal val BrandGradientEnd = Color(0xFF7C3AED)

// Заливные пилюли задержки в списке узлов. Одни значения на обе темы: текст
// в пилюле белый, и фону нужна насыщенность, а не тон схемы — светло-зелёный
// DarkStatusConnected под белым текстом не прошёл бы по контрасту.
internal val DelayPillFast = Color(0xFF16A34A)
internal val DelayPillMedium = Color(0xFFD97706)
internal val DelayPillSlow = Color(0xFFDC2626)

/** Стрелка «отправлено» в строке трафика сессии; синий из утверждённого макета. */
internal val SessionUploadTint = Color(0xFF42A5F5)
