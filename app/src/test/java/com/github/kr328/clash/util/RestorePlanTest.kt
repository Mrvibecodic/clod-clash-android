package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RestorePlanTest {
    private fun item(source: String) = ProfileImports.Item(
        name = source,
        nameManual = false,
        source = source,
        interval = 0,
        intervalManual = false,
        secure = false,
        active = false,
    )

    private val valid: (String) -> Boolean = { it.startsWith("https://") }

    @Test
    fun `копия на том же телефоне — всё уже есть, не провал`() {
        val sources = listOf("https://a.example.com/s", "https://b.example.com/s")

        val plan = planRestore(sources.map(::item), sources.toSet(), valid)

        assertEquals(emptyList<ProfileImports.Item>(), plan.load)
        assertEquals(2, plan.present)
        assertEquals(emptyList<ProfileImports.Item>(), plan.rejected)
    }

    @Test
    fun `негодный адрес отброшен отдельно от уже имеющихся`() {
        val plan = planRestore(
            listOf(item("https://a.example.com/s"), item("ftp://b.example.com"), item("https://c.example.com/s")),
            setOf("https://a.example.com/s"),
            valid,
        )

        assertEquals(listOf("https://c.example.com/s"), plan.load.map { it.source })
        assertEquals(1, plan.present)
        assertEquals(listOf("ftp://b.example.com"), plan.rejected.map { it.source })
    }

    @Test
    fun `повтор адреса внутри копии — одна подписка`() {
        val plan = planRestore(
            listOf(
                item("https://a.example.com/s"),
                item("https://a.example.com/s"),
                item("https://b.example.com/s"),
                item("https://b.example.com/s"),
            ),
            setOf("https://b.example.com/s"),
            valid,
        )

        assertEquals(listOf("https://a.example.com/s"), plan.load.map { it.source })
        assertEquals(1, plan.present)
    }

    @Test
    fun `активность повтора не теряется`() {
        val plan = planRestore(
            listOf(item("https://a.example.com/s"), item("https://a.example.com/s").copy(active = true)),
            emptySet(),
            valid,
        )

        assertEquals(listOf(true), plan.load.map { it.active })
    }
}
