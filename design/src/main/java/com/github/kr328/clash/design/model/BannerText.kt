package com.github.kr328.clash.design.model

/**
 * Маркеры цвета внутри провайдерских баннеров (`announce`, `clod-promo`).
 *
 * Панель красит отдельное слово, приклеив к нему код `#RRGGBB`:
 * `#EF4444ВАЖНО:` показывает `ВАЖНО:` красным. Синтаксис — Prizrak-Box,
 * панель, настроенная под него, работает без изменений:
 *
 * * маркер относится к слову сразу за ним и кончается на ближайшем пробеле;
 * * маркер с пробелом после кода — обычный текст, а не маркер;
 * * всё, что не шесть шестнадцатеричных знаков (`#XYZ`, `#12`, одинокая
 *   решётка), остаётся текстом.
 *
 * Цвет применяется как прислали, в светлой и тёмной теме одинаково.
 *
 * Разбор зеркалит `parseBannerText` на ПК и `truncateBanner` в ядре знак в знак:
 * маркер нулевой ширины, цепочка маркеров (`#AAAAAA#BBBBBBслово`) съедается
 * целиком и красит последний, «пробел» — Unicode White_Space (как
 * `char::is_whitespace` в Rust и `unicode.IsSpace` в Go), а не `Char.isWhitespace()`
 * Kotlin, у которого набор другой (нет NEL, есть управляющие `\u001C`–`\u001F`).
 * Разойдутся стороны — лимит видимых знаков в ядре разъедется с тем, что
 * видит пользователь.
 */
data class BannerFragment(
    val text: String,
    /** `#RRGGBB`, если провайдер просил цвет. */
    val color: String? = null,
)

private const val COLOUR_MARKER_LEN = 7

/** Unicode White_Space — тот же набор, что у `char::is_whitespace` в Rust. */
private fun isUnicodeWhitespace(ch: Char): Boolean = when (ch) {
    in '\u0009'..'\u000D', ' ', '\u0085', ' ', ' ',
    in ' '..' ', ' ', ' ', ' ', ' ', '　',
    -> true
    else -> false
}

private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** `#RRGGBB`, приклеенный к не-пробелу, или `null`. */
private fun markerAt(text: String, index: Int): String? {
    if (text[index] != '#' || index + COLOUR_MARKER_LEN >= text.length) return null
    if (isUnicodeWhitespace(text[index + COLOUR_MARKER_LEN])) return null

    val code = text.substring(index + 1, index + COLOUR_MARKER_LEN)
    if (!code.all { it.isAsciiHexDigit() }) return null

    return code
}

/** Делит текст баннера на обычные и покрашенные куски по порядку. */
fun parseBannerText(text: String): List<BannerFragment> {
    val fragments = mutableListOf<BannerFragment>()
    val buf = StringBuilder()
    var bufColor: String? = null

    fun flush() {
        if (buf.isEmpty()) return
        fragments += BannerFragment(buf.toString(), bufColor)
        buf.clear()
    }

    var color: String? = null
    var index = 0

    while (index < text.length) {
        val code = markerAt(text, index)
        if (code != null) {
            // Нулевая ширина, как в счётчике лимита ядра: цепочка маркеров
            // съедается целиком, красит последний.
            color = "#$code"
            index += COLOUR_MARKER_LEN
            continue
        }

        val ch = text[index]
        if (isUnicodeWhitespace(ch)) {
            color = null
        }
        if (buf.isEmpty() || bufColor != color) {
            flush()
            bufColor = color
        }
        buf.append(ch)
        index += 1
    }
    flush()

    return fragments
}
