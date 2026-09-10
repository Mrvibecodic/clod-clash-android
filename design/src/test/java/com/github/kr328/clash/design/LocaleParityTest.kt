package com.github.kr328.clash.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocaleParityTest {
    private val declarations = Regex("""<string name="([^"]+)"([^>]*)>""")

    private fun resolve(path: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile

        while (dir != null) {
            for (candidate in listOf(File(dir, path), File(dir, "design/$path"))) {
                if (candidate.isFile) return candidate
            }

            dir = dir.parentFile
        }

        throw AssertionError("$path not found from ${System.getProperty("user.dir")}")
    }

    private fun translatableKeys(path: String): Set<String> =
        declarations.findAll(resolve(path).readText())
            .filterNot { it.groupValues[2].contains("translatable=\"false\"") }
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun everyTranslatableStringHasRussian() {
        val base = translatableKeys("src/main/res/values/strings.xml")
        val ru = translatableKeys("src/main/res/values-ru/strings.xml")

        assertTrue("base strings not found", base.size > 100)
        assertEquals("keys without values-ru", emptySet<String>(), base - ru)
        assertEquals("keys only in values-ru", emptySet<String>(), ru - base)
    }
}
