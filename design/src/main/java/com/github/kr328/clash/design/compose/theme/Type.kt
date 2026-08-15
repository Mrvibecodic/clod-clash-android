package com.github.kr328.clash.design.compose.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Типографика Clod Clash.
 *
 * Гарнитура системная (на Android это Roboto): своих шрифтов не тащим — каждый
 * добавленный вес это лишние ~100 КБ в APK.
 *
 * От базовой Material 3 отличается ровно один стиль — таймер сессии: он
 * набирается моноширинными цифрами, чтобы секунды не дёргали строку при каждом
 * тике. Крупного статуса «Отключено» / «Подключено» в 32 sp здесь больше нет:
 * состояние показывает цвет кнопки, а словом его подписывает маленькая пилюля.
 */
private val Default = Typography()

/**
 * Таймер сессии — подписью внутри кнопки подключения.
 *
 * `FontFamily.Monospace` — чтобы ширина цифр не менялась: с обычной гарнитурой
 * «1» уже «0», и строка прыгает раз в секунду, а внутри круга это заметно
 * вдвойне — цифры ездили бы относительно иконки.
 */
val TimerTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 18.sp,
)

val ClodTypography: Typography = Default.copy(
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)
