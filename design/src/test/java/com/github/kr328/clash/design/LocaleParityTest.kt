package com.github.kr328.clash.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocaleParityTest {
    private val declarations = Regex("""<string name="([^"]+)"([^>]*)>""")

    private val modules = listOf("design", "service", "common")

    private val root: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile

        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return@run dir

            dir = dir.parentFile
        }

        throw AssertionError("repository root not found from ${System.getProperty("user.dir")}")
    }

    private fun resolve(module: String, locale: String): File {
        val file = File(root, "$module/src/main/res/$locale/strings.xml")

        assertTrue("${file.path} not found", file.isFile)

        return file
    }

    private fun translatableKeys(module: String, locale: String): Set<String> =
        declarations.findAll(resolve(module, locale).readText())
            .filterNot { it.groupValues[2].contains("translatable=\"false\"") }
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun everyTranslatableStringHasRussian() {
        for (module in modules) {
            val base = translatableKeys(module, "values")
            val ru = translatableKeys(module, "values-ru")

            assertTrue("$module: base strings not found", base.isNotEmpty())
            assertEquals("$module: keys without values-ru", emptySet<String>(), base - ru)
            assertEquals("$module: keys only in values-ru", emptySet<String>(), ru - base)
        }
    }

    @Test
    fun everyModuleWithStringsIsChecked() {
        val owners = root.listFiles()
            .orEmpty()
            .filter { File(it, "src/main/res/values/strings.xml").isFile }
            .map { it.name }
            .sorted()

        assertEquals(modules.sorted(), owners)
    }

    @Test
    fun theBiggestModuleIsFound() {
        assertTrue(translatableKeys("design", "values").size > 100)
    }
}
