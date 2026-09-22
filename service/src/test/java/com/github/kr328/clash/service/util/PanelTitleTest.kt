package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.PanelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelTitleTest {
    private val max = 60

    @Test
    fun `короткое название не меняется`() {
        assertEquals("Подписка", truncateTitle("Подписка"))
    }

    @Test
    fun `название по размеру не меняется`() {
        val value = "я".repeat(max)

        assertEquals(value, truncateTitle(value))
    }

    @Test
    fun `длинное название обрезается и получает многоточие`() {
        assertEquals("я".repeat(max) + "…", truncateTitle("я".repeat(max + 140)))
    }

    @Test
    fun `хвостовые пробелы срезаются`() {
        assertEquals("я".repeat(max - 1) + "…", truncateTitle("я".repeat(max - 1) + "  я"))
    }

    @Test
    fun `обрезка идемпотентна`() {
        for (
        value in listOf(
            "я".repeat(max + 1),
            "я".repeat(max + 200),
            "я".repeat(max - 1) + " я",
            "a".repeat(max * 3),
            "🙂" + "🙃".repeat(max + 10),
        )
        ) {
            val once = truncateTitle(value)

            assertEquals(once, truncateTitle(once))
            assertTrue(once.codePointCount(0, once.length) <= max + 1)
        }
    }

    @Test
    fun `составные символы не разрезаются посередине`() {
        val value = "🙃".repeat(max + 10)
        val once = truncateTitle(value)

        assertEquals(max, once.dropLast(1).codePointCount(0, once.length - 1))
        assertEquals("🙃".repeat(max) + "…", once)
    }

    @Test
    fun `имя подписки берётся из панели, иначе сохранённое`() {
        assertEquals("a", profileDisplayName(null, "a"))
        assertEquals("a", profileDisplayName(PanelInfo(title = " "), "a"))
        assertEquals("T", profileDisplayName(PanelInfo(title = "T"), "a"))
    }
}
