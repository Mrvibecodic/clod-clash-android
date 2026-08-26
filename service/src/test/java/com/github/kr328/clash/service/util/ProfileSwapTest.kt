package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ProfileSwapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Interrupted : RuntimeException()

    private fun imported(): File = tmp.newFolder("imported")

    private fun dir(parent: File, name: String, vararg files: Pair<String, String>): File {
        val d = parent.resolve(name).apply { mkdirs() }
        files.forEach { (n, body) -> d.resolve(n).apply { parentFile?.mkdirs() }.writeText(body) }
        return d
    }

    private fun snapshot(d: File): Map<String, String> =
        d.walk().filter { it.isFile }.associate { it.relativeTo(d).path to it.readText() }

    private val oldVersion = arrayOf(
        "config.yaml" to "old",
        "providers/rules.yaml" to "old-rules",
        "panel.json" to "{\"v\":1}",
        "alerts.json" to "{\"shown\":[80]}",
        "migration.json" to "{\"hops\":1}",
    )

    private val newVersion = arrayOf(
        "config.yaml" to "new",
        "providers/rules.yaml" to "new-rules",
        "panel.json" to "{\"v\":2}",
    )

    @Test
    fun `обычное обновление заменяет папку целиком и сохраняет свои файлы`() {
        val root = imported()
        val live = dir(root, "p", *oldVersion)
        val fresh = dir(tmp.root, "processing", *newVersion)

        ProfileSwap.replace(live, fresh)

        assertEquals(
            mapOf(
                "config.yaml" to "new",
                "providers/rules.yaml" to "new-rules",
                "panel.json" to "{\"v\":2}",
                "alerts.json" to "{\"shown\":[80]}",
                "migration.json" to "{\"hops\":1}",
            ),
            snapshot(live),
        )
        assertFalse(fresh.exists())
        assertFalse(ProfileSwap.staleOf(live).exists())
        assertEquals(listOf("p"), root.list()!!.toList())
    }

    @Test
    fun `первый импорт без живой папки`() {
        val root = imported()
        val live = root.resolve("p")
        val fresh = dir(tmp.root, "processing", *newVersion)

        ProfileSwap.replace(live, fresh)

        assertEquals("new", live.resolve("config.yaml").readText())
        assertFalse(fresh.exists())
    }

    @Test
    fun `свой файл из живой папки главнее копии в свежей`() {
        val root = imported()
        val live = dir(root, "p", "config.yaml" to "old", "alerts.json" to "live")
        val fresh = dir(tmp.root, "processing", "config.yaml" to "new", "alerts.json" to "stale-copy")

        ProfileSwap.replace(live, fresh)

        assertEquals("live", live.resolve("alerts.json").readText())
    }

    @Test
    fun `свежая папка обязана быть целой`() {
        val root = imported()
        val live = dir(root, "p", *oldVersion)

        for (fresh in listOf(tmp.root.resolve("missing"), dir(tmp.root, "half", "providers/rules.yaml" to "x"))) {
            try {
                ProfileSwap.replace(live, fresh)
                fail()
            } catch (e: IllegalArgumentException) {
            }
        }

        assertEquals(oldVersion.toMap(), snapshot(live))
    }

    @Test
    fun `корень imported создаётся при первом импорте`() {
        val live = tmp.root.resolve("files/imported/p")
        val fresh = dir(tmp.root, "processing", *newVersion)

        ProfileSwap.replace(live, fresh)

        assertEquals("new", live.resolve("config.yaml").readText())
    }

    @Test
    fun `папка, пересозданная ядром между переименованиями, не мешает замене`() {
        val root = imported()
        val live = dir(root, "p", *oldVersion)
        val fresh = dir(tmp.root, "processing", *newVersion)

        ProfileSwap.replace(live, fresh) {
            if (it == ProfileSwap.Step.PROMOTE_FRESH) dir(root, "p", "providers/late.yaml" to "written by core")
        }

        assertEquals("new", live.resolve("config.yaml").readText())
        assertFalse(live.resolve("providers/late.yaml").exists())
        assertEquals(listOf("p"), root.list()!!.toList())
    }

    @Test
    fun `нечитаемый свой файл не срывает обновление`() {
        val root = imported()
        val live = dir(root, "p", *oldVersion)
        val fresh = dir(tmp.root, "processing", *newVersion)
        dir(fresh, "alerts.json", "nested" to "x")
        val warnings = mutableListOf<String>()

        ProfileSwap.replace(live, fresh, warn = { warnings += it })

        assertEquals("new", live.resolve("config.yaml").readText())
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("alerts.json"))
    }

    @Test
    fun `обрыв на каждом шаге и ремонт оставляют ровно одну целую версию`() {
        for (step in ProfileSwap.Step.values()) {
            val root = tmp.newFolder("imported-$step")
            val live = dir(root, "p", *oldVersion)
            val fresh = dir(tmp.newFolder("work-$step"), "processing", *newVersion)

            try {
                ProfileSwap.replace(live, fresh) { if (it == step) throw Interrupted() }
                fail("$step")
            } catch (e: Interrupted) {
            }

            val repairs = ProfileSwap.repair(root)

            assertTrue("$step", live.isDirectory)
            assertFalse("$step", ProfileSwap.staleOf(live).exists())
            assertEquals("$step", listOf("p"), root.list()!!.toList())

            val config = live.resolve("config.yaml").readText()
            val rules = live.resolve("providers/rules.yaml").readText()

            when (config) {
                "old" -> assertEquals("$step", "old-rules", rules)
                "new" -> assertEquals("$step", "new-rules", rules)
                else -> fail("$step: $config")
            }

            assertEquals("$step", "{\"shown\":[80]}", live.resolve("alerts.json").readText())
            assertEquals("$step", "{\"hops\":1}", live.resolve("migration.json").readText())

            when (step) {
                ProfileSwap.Step.KEEP_OWN_FILES, ProfileSwap.Step.PARK_LIVE -> {
                    assertEquals("$step", "old", config)
                    assertTrue("$step", repairs.isEmpty())
                }
                ProfileSwap.Step.PROMOTE_FRESH -> {
                    assertEquals("$step", "old", config)
                    assertTrue("$step", repairs.single() is ProfileSwap.Repair.Restored)
                }
                ProfileSwap.Step.DROP_STALE -> {
                    assertEquals("$step", "new", config)
                    assertTrue("$step", repairs.single() is ProfileSwap.Repair.Dropped)
                }
            }
        }
    }

    @Test
    fun `повторный ремонт ничего не меняет`() {
        val root = imported()
        val live = dir(root, "p", *oldVersion)
        val fresh = dir(tmp.root, "processing", *newVersion)

        try {
            ProfileSwap.replace(live, fresh) { if (it == ProfileSwap.Step.PROMOTE_FRESH) throw Interrupted() }
        } catch (e: Interrupted) {
        }

        assertEquals(1, ProfileSwap.repair(root).size)
        val after = snapshot(live)

        assertTrue(ProfileSwap.repair(root).isEmpty())
        assertEquals(after, snapshot(live))
    }

    @Test
    fun `ремонт чужих полусостояний`() {
        val root = imported()
        dir(root, "a.old", "config.yaml" to "a-old")
        dir(root, "b", "config.yaml" to "b-live")
        dir(root, "b.old", "config.yaml" to "b-old")
        dir(root, "c", "config.yaml" to "c-live")
        root.resolve("d.old").mkdirs()
        root.resolve("e.old").writeText("not a directory")
        dir(root, "f", "providers/rules.yaml" to "orphan")
        dir(root, "f.old", "config.yaml" to "f-old")
        root.resolve("junk.txt").writeText("x")

        val repairs = ProfileSwap.repair(root).associate { it.name to it }

        assertTrue(repairs["a"] is ProfileSwap.Repair.Restored)
        assertTrue(repairs["b"] is ProfileSwap.Repair.Dropped)
        assertTrue(repairs["f"] is ProfileSwap.Repair.Restored)
        assertEquals(3, repairs.size)

        assertEquals("a-old", root.resolve("a/config.yaml").readText())
        assertEquals("b-live", root.resolve("b/config.yaml").readText())
        assertFalse(root.resolve("b.old").exists())
        assertEquals("c-live", root.resolve("c/config.yaml").readText())
        assertFalse(root.resolve("d").exists())
        assertEquals("f-old", root.resolve("f/config.yaml").readText())
        assertFalse(root.resolve("f/providers").exists())
        assertEquals(listOf("a", "b", "c", "f", "junk.txt"), root.list()!!.sorted())
    }

    @Test
    fun `ремонт пустого или отсутствующего каталога`() {
        assertTrue(ProfileSwap.repair(tmp.root.resolve("nothing")).isEmpty())
        assertTrue(ProfileSwap.repair(tmp.newFolder("empty")).isEmpty())
    }

    @Test
    fun `старый остаток удаляется перед заменой`() {
        val root = imported()
        val live = dir(root, "p", *oldVersion)
        dir(root, "p.old", "config.yaml" to "ancient")
        val fresh = dir(tmp.root, "processing", *newVersion)

        ProfileSwap.replace(live, fresh)

        assertEquals("new", live.resolve("config.yaml").readText())
        assertEquals(listOf("p"), root.list()!!.toList())
    }

    @Test
    fun `замена поверх обрывка с целым остатком не теряет свои файлы`() {
        val root = imported()
        dir(root, "p", "providers/late.yaml" to "written by core")
        dir(root, "p.old", *oldVersion)
        val fresh = dir(tmp.root, "processing", *newVersion)

        ProfileSwap.replace(root.resolve("p"), fresh)

        assertEquals("new", root.resolve("p/config.yaml").readText())
        assertEquals("{\"shown\":[80]}", root.resolve("p/alerts.json").readText())
        assertEquals("{\"hops\":1}", root.resolve("p/migration.json").readText())
        assertEquals(listOf("p"), root.list()!!.toList())
    }

    @Test
    fun `провал продвижения возвращает живую папку`() {
        val root = imported()
        val live = dir(root, "p", *oldVersion)
        val fresh = dir(tmp.root, "processing", *newVersion)

        try {
            ProfileSwap.replace(live, fresh) {
                if (it == ProfileSwap.Step.PROMOTE_FRESH) fresh.deleteRecursively()
            }
            fail()
        } catch (e: IOException) {
        }

        assertEquals("old", live.resolve("config.yaml").readText())
        assertFalse(ProfileSwap.staleOf(live).exists())
    }
}
