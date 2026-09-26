package com.github.kr328.clash.design.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Фикстуры общие с ПК (banner-text.fixtures.json) и с truncateBanner ядра. */
class BannerTextTest {
    private data class Fixture(
        val name: String,
        val input: String,
        val fragments: List<Pair<String, String?>>,
        val visible: Int,
    )

    private val fixtures = listOf(
        Fixture(
            "обычный текст режется по лимиту",
            "Оплатите подписку до пятницы",
            listOf("Оплатите подписку до пятницы" to null),
            28,
        ),
        Fixture(
            "маркер красит слово и не входит в лимит",
            "#EF4444ВАЖНО: продление",
            listOf("ВАЖНО:" to "#EF4444", " продление" to null),
            16,
        ),
        Fixture(
            "цепочка маркеров нулевой ширины, красит последний",
            "#AAAAAA#BBBBBBскидка",
            listOf("скидка" to "#BBBBBB"),
            6,
        ),
        Fixture(
            "маркер перед пробелом — обычный текст и входит в лимит",
            "#EF4444 не маркер",
            listOf("#EF4444 не маркер" to null),
            17,
        ),
        Fixture(
            "NEL после кода — пробел, маркера нет",
            "#EF4444\u0085хвост",
            listOf("#EF4444\u0085хвост" to null),
            13,
        ),
        Fixture(
            "BOM — не пробел, маркер работает",
            "#EF4444﻿старт",
            listOf("﻿старт" to "#EF4444"),
            6,
        ),
        Fixture(
            "вход короче лимита возвращается как есть",
            "#EF4444ВАЖНО",
            listOf("ВАЖНО" to "#EF4444"),
            5,
        ),
    )

    @Test
    fun `фикстуры баннеров разбираются как на ПК`() {
        for (fixture in fixtures) {
            val parsed = parseBannerText(fixture.input)

            assertEquals(fixture.name, fixture.fragments, parsed.map { it.text to it.color })
            assertEquals(fixture.name, fixture.visible, parsed.sumOf { it.text.codePointCount(0, it.text.length) })
        }
    }

    @Test
    fun `цвет кончается на пробеле, следующее слово обычное`() {
        assertEquals(
            listOf("раз" to "#EF4444", " " to null, "два" to "#00FF00", " три" to null),
            parseBannerText("#EF4444раз #00FF00два три").map { it.text to it.color },
        )
    }

    @Test
    fun `не шесть шестнадцатеричных знаков — текст`() {
        for (text in listOf("#XYZ123слово", "#12 слово", "#", "цена #100 руб", "#EF444", "#EF4444")) {
            assertEquals(text, listOf(text to null), parseBannerText(text).map { it.text to it.color })
        }
    }

    @Test
    fun `берутся ровно шесть знаков, седьмой красится`() {
        assertEquals(listOf("7" to "#123456"), parseBannerText("#1234567").map { it.text to it.color })
    }

    @Test
    fun `пустой текст — пусто`() {
        assertEquals(emptyList<BannerFragment>(), parseBannerText(""))
    }
}
