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
 * добавленный вес это лишние ~100 КБ в APK, а на экранах клиента крупного текста
 * ровно два вида — статус подключения и таймер сессии.
 *
 * От базовой Material 3 отличаются только те стили, которые в макете заданы явно:
 * статус на главном экране 32 sp, таймер сессии — моноширинными цифрами, чтобы
 * секунды не дёргали строку при каждом тике.
 */
private val Default = Typography()

/** Крупный статус на главном экране: «Отключено» / «Подключено». */
val StatusTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 38.sp,
)

/**
 * Таймер сессии. `FontFamily.Monospace` — чтобы ширина цифр не менялась: с обычной
 * гарнитурой «1» уже «0», и строка прыгает раз в секунду.
 */
val TimerTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 34.sp,
    lineHeight = 40.sp,
)

val ClodTypography: Typography = Default.copy(
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)
